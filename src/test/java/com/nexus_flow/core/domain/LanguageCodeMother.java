package com.nexus_flow.core.domain;

import com.nexus_flow.core.ddd.value_objects.LanguageCode;

import java.util.ArrayList;
import java.util.List;

public final class LanguageCodeMother {

    public static LanguageCode create(String value) {
        return new LanguageCode(value);
    }

    public static LanguageCode random() {
        return create(MotherCreator.random().address().countryCode());
    }

    public static LanguageCode randomNotIn(List<LanguageCode> languageCodes) {
        LanguageCode languageCode = random();
        while (languageCodes.contains(languageCode)) {
            languageCode = random();
        }
        return languageCode;
    }

    public static List<LanguageCode> randomList() {

        int                size         = MotherCreator.random().number().numberBetween(1, 5);
        List<LanguageCode> languageList = new ArrayList<>(size);

        for (int i = 0; i < size; i++) {
            languageList.add(randomNotIn(languageList));
        }

        return languageList;
    }

    public static List<LanguageCode> randomListWithoutLanguageCode(LanguageCode languageCode) {

        int                size                        = MotherCreator.random().number().numberBetween(1, 5);
        List<LanguageCode> languageList                = new ArrayList<>(size);
        List<LanguageCode> languageListShouldNotRepeat = new ArrayList<>(size + 1);
        languageListShouldNotRepeat.add(languageCode);

        for (int i = 0; i < size; i++) {
            languageList.add(randomNotIn(languageListShouldNotRepeat));
        }

        return languageList;
    }


}
