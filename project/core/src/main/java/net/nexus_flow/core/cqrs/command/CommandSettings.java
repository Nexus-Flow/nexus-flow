package net.nexus_flow.core.cqrs.command;

import java.util.EnumSet;
import java.util.logging.Logger;

public class CommandSettings {
    private static final Logger logger = Logger.getLogger(CommandSettings.class.getName());

    private final EnumSet<IgnoredCondition> ignoredConditionsWhenInvokedFromCommand = EnumSet.noneOf(IgnoredCondition.class);
    private final EnumSet<IgnoredCondition> ignoredConditionsWhenInvokedFromEvent = EnumSet.noneOf(IgnoredCondition.class);

    public static Builder builder() {
        return new Builder();
    }

    public void ignoreConditionWhenInvokedFromCommand(IgnoredCondition condition) {
        ignoredConditionsWhenInvokedFromCommand.add(condition);
    }

    public void doNotIgnoreConditionWhenInvokedFromCommand(IgnoredCondition condition) {
        if (!ignoredConditionsWhenInvokedFromCommand.contains(condition)) {
            logger.warning("The condition: " + condition + " is currently not being ignored. Cannot perform 'do not ignore' on it.");
            return;
        }
        ignoredConditionsWhenInvokedFromCommand.remove(condition);
    }

    public boolean shouldIgnoreConditionWhenInvokedFromCommand(IgnoredCondition condition) {
        return ignoredConditionsWhenInvokedFromCommand.contains(condition);
    }

    public void ignoreConditionWhenInvokedFromEvent(IgnoredCondition condition) {
        ignoredConditionsWhenInvokedFromEvent.add(condition);
    }

    public void doNotIgnoreConditionWhenInvokedFromEvent(IgnoredCondition condition) {
        if (!ignoredConditionsWhenInvokedFromEvent.contains(condition)) {
            logger.warning("The condition: " + condition + " is currently not being ignored. Cannot perform 'do not ignore' on it.");
            return;
        }
        ignoredConditionsWhenInvokedFromEvent.remove(condition);
    }

    public boolean shouldIgnoreConditionWhenInvokedFromEvent(IgnoredCondition condition) {
        return ignoredConditionsWhenInvokedFromEvent.contains(condition);
    }

    public static class Builder {
        private final CommandSettings commandSettings = new CommandSettings();

        public Builder ignoreConditionWhenInvokedFromCommand(IgnoredCondition condition) {
            commandSettings.ignoreConditionWhenInvokedFromCommand(condition);
            return this;
        }

        public Builder ignoreConditionWhenInvokedFromEvent(IgnoredCondition condition) {
            commandSettings.ignoreConditionWhenInvokedFromEvent(condition);
            return this;
        }

        public Builder doNotIgnoreConditionWhenInvokedFromCommand(IgnoredCondition condition) {
            commandSettings.doNotIgnoreConditionWhenInvokedFromCommand(condition);
            return this;
        }

        public Builder doNotIgnoreConditionWhenInvokedFromEvent(IgnoredCondition condition) {
            commandSettings.doNotIgnoreConditionWhenInvokedFromEvent(condition);
            return this;
        }

        public CommandSettings build() {
            return commandSettings;
        }
    }
}