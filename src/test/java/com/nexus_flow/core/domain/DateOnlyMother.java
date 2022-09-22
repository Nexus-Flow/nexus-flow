package com.nexus_flow.core.domain;

import com.nexus_flow.core.ddd.value_objects.DateValueObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.TimeUnit;

public final class DateOnlyMother {

    public static final String ONLY_DATE = DateValueObject.ONLY_DATE;


    public static String randomPastString() {
        return new SimpleDateFormat(ONLY_DATE).format(randomDate());
    }


    private static Date randomDate() {
        return MotherCreator.random().date().past(300, TimeUnit.DAYS);
    }
}
