package com.nexus_flow.core.configurations.tests;

import org.springframework.test.context.ActiveProfilesResolver;

/**This class resolve the profile during the tests step. It's intended in order to filter acceptance and
 * integration tests during deploying process. This tests will be executed after deploying via a maven command.
 */
public class NexusFlowActiveProfilesResolver implements ActiveProfilesResolver {

    @Override
    public String[] resolve(final Class<?> aClass) {
        final String activeProfile = System.getProperty("spring.profiles.active");
        return new String[]{(activeProfile == null || activeProfile.equals("default"))
                ? "local" : activeProfile};
    }
}
