package org.sonar.plugins.findbugs;

import org.codehaus.staxmate.SMInputFactory;
import org.codehaus.staxmate.in.SMHierarchicCursor;
import org.codehaus.staxmate.in.SMInputCursor;
import org.sonar.api.rule.RuleStatus;
import org.sonar.api.rule.Severity;
import org.sonar.api.rules.RuleType;
import org.sonar.api.server.debt.DebtRemediationFunction;
import org.sonar.api.server.rule.RuleParamType;
import org.sonar.api.server.rule.RulesDefinition;
import org.sonar.api.server.rule.RulesDefinitionXmlLoader;
import org.sonar.check.Cardinality;

import javax.annotation.Nullable;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

import static java.lang.String.format;
import static org.apache.commons.lang.StringUtils.*;

public class FindbugsRulesDefinitionXmlLoader extends RulesDefinitionXmlLoader {
    private enum DescriptionFormat {
        HTML, MARKDOWN
    }

    @Override
    public void load(RulesDefinition.NewRepository repo, Reader reader) {
        XMLInputFactory xmlFactory = XMLInputFactory.newInstance();
        xmlFactory.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);
        xmlFactory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, Boolean.FALSE);
        // just so it won't try to load DTD in if there's DOCTYPE
        xmlFactory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        xmlFactory.setProperty(XMLInputFactory.IS_VALIDATING, Boolean.FALSE);
        SMInputFactory inputFactory = new SMInputFactory(xmlFactory);
        try {
            SMHierarchicCursor rootC = inputFactory.rootElementCursor(reader);
            rootC.advance(); // <rules>

            SMInputCursor rulesC = rootC.childElementCursor("rule");
            while (rulesC.getNext() != null) {
                // <rule>
                processRule(repo, rulesC);
            }

        } catch (XMLStreamException e) {
            throw new IllegalStateException("XML is not valid", e);
        }
    }

    private static void processRule(RulesDefinition.NewRepository repo, SMInputCursor ruleC) throws XMLStreamException {
        String key = null;
        String name = null;
        String description = null;
        // enum is not used as variable type as we want to raise an exception with the rule key when format is not supported
        String descriptionFormat = DescriptionFormat.HTML.name();
        String internalKey = null;
        String severity = Severity.defaultSeverity();
        String type = null;
        RuleStatus status = RuleStatus.defaultStatus();
        boolean template = false;
        String gapDescription = null;
        String debtRemediationFunction = null;
        String debtRemediationFunctionGapMultiplier = null;
        String debtRemediationFunctionBaseEffort = null;
        List<ParamStruct> params = new ArrayList<>();
        List<String> tags = new ArrayList<>();
        SecurityStandards securityStandards = new SecurityStandards();

        /* BACKWARD COMPATIBILITY WITH VERY OLD FORMAT */
        String keyAttribute = ruleC.getAttrValue("key");
        if (isNotBlank(keyAttribute)) {
            key = trim(keyAttribute);
        }
        String priorityAttribute = ruleC.getAttrValue("priority");
        if (isNotBlank(priorityAttribute)) {
            severity = trim(priorityAttribute);
        }

        SMInputCursor cursor = ruleC.childElementCursor();
        while (cursor.getNext() != null) {
            String nodeName = cursor.getLocalName();

            if (equalsIgnoreCase("name", nodeName)) {
                name = nodeValue(cursor);

            } else if (equalsIgnoreCase("type", nodeName)) {
                type = nodeValue(cursor);

            } else if (equalsIgnoreCase("description", nodeName)) {
                description = nodeValue(cursor);

            } else if (equalsIgnoreCase("descriptionFormat", nodeName)) {
                descriptionFormat = nodeValue(cursor);

            } else if (equalsIgnoreCase("key", nodeName)) {
                key = nodeValue(cursor);

            } else if (equalsIgnoreCase("configKey", nodeName)) {
                // deprecated field, replaced by internalKey
                internalKey = nodeValue(cursor);

            } else if (equalsIgnoreCase("internalKey", nodeName)) {
                internalKey = nodeValue(cursor);

            } else if (equalsIgnoreCase("priority", nodeName) || equalsIgnoreCase("severity", nodeName)) {
                // "priority" is deprecated field and has been replaced by "severity"
                severity = nodeValue(cursor);

            } else if (equalsIgnoreCase("cardinality", nodeName)) {
                template = Cardinality.MULTIPLE == Cardinality.valueOf(nodeValue(cursor));

            } else if (equalsIgnoreCase("gapDescription", nodeName) || equalsIgnoreCase("effortToFixDescription", nodeName)) {
                gapDescription = nodeValue(cursor);

            } else if (equalsIgnoreCase("remediationFunction", nodeName) || equalsIgnoreCase("debtRemediationFunction", nodeName)) {
                debtRemediationFunction = nodeValue(cursor);

            } else if (equalsIgnoreCase("remediationFunctionBaseEffort", nodeName) || equalsIgnoreCase("debtRemediationFunctionOffset", nodeName)) {
                debtRemediationFunctionGapMultiplier = nodeValue(cursor);

            } else if (equalsIgnoreCase("remediationFunctionGapMultiplier", nodeName) || equalsIgnoreCase("debtRemediationFunctionCoefficient", nodeName)) {
                debtRemediationFunctionBaseEffort = nodeValue(cursor);

            } else if (equalsIgnoreCase("status", nodeName)) {
                String s = nodeValue(cursor);
                if (s != null) {
                    status = RuleStatus.valueOf(s);
                }

            } else if (equalsIgnoreCase("param", nodeName)) {
                params.add(processParameter(cursor));

            } else if (equalsIgnoreCase("tag", nodeName)) {
                tags.add(nodeValue(cursor));
            } else if (equalsIgnoreCase("securityStandards", nodeName)) {
                securityStandards = processSecurityStandards(cursor);
            }
        }

        try {
            RulesDefinition.NewRule rule = repo.createRule(key)
                    .setSeverity(severity)
                    .setName(name)
                    .setInternalKey(internalKey)
                    .setTags(tags.toArray(new String[tags.size()]))
                    .setTemplate(template)
                    .setStatus(status)
                    .setGapDescription(gapDescription);
            if (type != null) {
                rule.setType(RuleType.valueOf(type));
            }
            fillDescription(rule, descriptionFormat, description);
            fillRemediationFunction(rule, debtRemediationFunction, debtRemediationFunctionGapMultiplier, debtRemediationFunctionBaseEffort);
            fillParams(rule, params);
            fillSecurityStandards(rule, securityStandards);
        } catch (Exception e) {
            throw new IllegalStateException(format("Fail to load the rule with key [%s:%s]", repo.key(), key), e);
        }
    }

    private static void fillDescription(RulesDefinition.NewRule rule, String descriptionFormat, @Nullable String description) {
        if (isNotBlank(description)) {
            switch (DescriptionFormat.valueOf(descriptionFormat)) {
                case HTML:
                    rule.setHtmlDescription(description);
                    break;
                case MARKDOWN:
                    rule.setMarkdownDescription(description);
                    break;
                default:
                    throw new IllegalArgumentException("Value of descriptionFormat is not supported: " + descriptionFormat);
            }
        }
    }

    private static void fillRemediationFunction(RulesDefinition.NewRule rule, @Nullable String debtRemediationFunction,
                                                @Nullable String functionOffset, @Nullable String functionCoeff) {
        if (isNotBlank(debtRemediationFunction)) {
            DebtRemediationFunction.Type functionType = DebtRemediationFunction.Type.valueOf(debtRemediationFunction);
            rule.setDebtRemediationFunction(rule.debtRemediationFunctions().create(functionType, functionCoeff, functionOffset));
        }
    }

    private static void fillParams(RulesDefinition.NewRule rule, List<ParamStruct> params) {
        for (ParamStruct param : params) {
            rule.createParam(param.key)
                    .setDefaultValue(param.defaultValue)
                    .setType(param.type)
                    .setDescription(param.description);
        }
    }

    private static String nodeValue(SMInputCursor cursor) throws XMLStreamException {
        return trim(cursor.collectDescendantText(false));
    }

    private static class ParamStruct {

        String key;
        String description;
        String defaultValue;
        RuleParamType type = RuleParamType.STRING;
    }

    private static ParamStruct processParameter(SMInputCursor ruleC) throws XMLStreamException {
        ParamStruct param = new ParamStruct();

        // BACKWARD COMPATIBILITY WITH DEPRECATED FORMAT
        String keyAttribute = ruleC.getAttrValue("key");
        if (isNotBlank(keyAttribute)) {
            param.key = trim(keyAttribute);
        }

        // BACKWARD COMPATIBILITY WITH DEPRECATED FORMAT
        String typeAttribute = ruleC.getAttrValue("type");
        if (isNotBlank(typeAttribute)) {
            param.type = RuleParamType.parse(typeAttribute);
        }

        SMInputCursor paramC = ruleC.childElementCursor();
        while (paramC.getNext() != null) {
            String propNodeName = paramC.getLocalName();
            String propText = nodeValue(paramC);
            if (equalsIgnoreCase("key", propNodeName)) {
                param.key = propText;

            } else if (equalsIgnoreCase("description", propNodeName)) {
                param.description = propText;

            } else if (equalsIgnoreCase("type", propNodeName)) {
                param.type = RuleParamType.parse(propText);

            } else if (equalsIgnoreCase("defaultValue", propNodeName)) {
                param.defaultValue = propText;
            }
        }
        return param;
    }

    private static SecurityStandards processSecurityStandards(SMInputCursor ruleC) throws XMLStreamException {
        SecurityStandards securityStandards = new SecurityStandards();

        SMInputCursor ss = ruleC.childElementCursor();
        while (ss.getNext() != null) {
            String name = ss.getLocalName();
            String[] values = nodeValue(ss).split(",");
            if (equalsIgnoreCase("cwe", name)) {
                securityStandards.CWE = new int[values.length];
                for (int i = 0; i < values.length; i++) {
                    securityStandards.CWE[i] = Integer.parseInt(values[i]);
                }
            } else if (equalsIgnoreCase("OWASP", name)) {
                securityStandards.OWASP = values;

            }
        }
        return securityStandards;
    }

    private static void fillSecurityStandards(RulesDefinition.NewRule rule, SecurityStandards securityStandards) {
        for (String s : securityStandards.OWASP) {
            rule.addOwaspTop10(RulesDefinition.OwaspTop10.valueOf(s));
        }
        rule.addCwe(securityStandards.CWE);
    }

    static class SecurityStandards {
        int[] CWE = {};
        String[] OWASP = {};
    }
}
