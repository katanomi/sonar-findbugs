package org.sonar.plugins.findbugs.profiles;

import org.sonar.api.server.profile.BuiltInQualityProfilesDefinition;
import org.sonar.plugins.findbugs.FindbugsProfileImporter;
import org.sonar.plugins.java.Java;

import java.io.InputStreamReader;
import java.io.Reader;

public class AlaudaProfile implements BuiltInQualityProfilesDefinition {

  public static final String ALAUDA_PROFILE_NAME = "FindBugs + FB-Contrib + Security + Sonar way";
  private final FindbugsProfileImporter importer;

  public AlaudaProfile(FindbugsProfileImporter importer) {
    this.importer = importer;
  }

  @Override
  public void define(Context context) {
    Reader findbugsProfile = new InputStreamReader(this.getClass().getResourceAsStream(
            "/org/sonar/plugins/findbugs/profile-alauda-aio.xml"));
    NewBuiltInQualityProfile profile = context.createBuiltInQualityProfile(ALAUDA_PROFILE_NAME, Java.KEY);
    importer.importProfile(findbugsProfile, profile);

    profile.done();
  }

}
