package com.nexus_flow.core.sagas.domain;

import com.nexus_flow.core.ddd.Utils;
import com.nexus_flow.core.domain.DateTimeUTCMother;
import com.nexus_flow.core.domain.UuidMother;

import java.time.ZonedDateTime;

public class OneSagaCommandMother {

    public static OneSagaCommand create(String id, ZonedDateTime occurredOn) {
        return new OneSagaCommand(id, Utils.dateToString(occurredOn));
    }

    public static OneSagaCommand random() {
        return new OneSagaCommand(UuidMother.random(), Utils.dateToString(DateTimeUTCMother.randomPastForEvents()));
    }

}
