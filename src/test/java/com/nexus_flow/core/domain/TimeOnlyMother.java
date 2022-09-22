package com.nexus_flow.core.domain;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.TimeUnit;

public class TimeOnlyMother {

    public static final String ONLY_TIME = "HH:mm";

    public TimeOnlyMother() {
    }

    public static String randomString() {
        return (new SimpleDateFormat(ONLY_TIME)).format(randomDate());
    }

    private static Date randomDate() {
        return MotherCreator.random().date().past(300, TimeUnit.DAYS);
    }

}
