package com.nexus_flow.core.sagas.domain;

import com.nexus_flow.core.ddd.Utils;
import com.nexus_flow.core.domain.DateTimeUTCMother;
import com.nexus_flow.core.domain.UuidMother;

import java.time.ZonedDateTime;

public class NotTriggerCommandMother {

    public static NotTriggerForThisSagaCommand create(String id, ZonedDateTime occurredOn) {
        return new NotTriggerForThisSagaCommand(id, Utils.dateToString(occurredOn));
    }

    public static NotTriggerForThisSagaCommand random() {
        return new NotTriggerForThisSagaCommand(UuidMother.random(), Utils.dateToString(DateTimeUTCMother.randomPastForEvents()));
    }

}
