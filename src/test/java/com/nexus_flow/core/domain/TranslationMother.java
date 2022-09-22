package com.nexus_flow.core.domain;

import com.nexus_flow.core.ddd.value_objects.LanguageCode;
import com.nexus_flow.core.ddd.value_objects.Translation;
import com.nexus_flow.core.ddd.value_objects.TranslationValue;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public final class TranslationMother {

    public static Translation create(String language, String translation) {
        return new Translation(new LanguageCode(language), new TranslationValue(translation));
    }

    public static Translation random() {
        return randomWithLanguageCode(LanguageCodeMother.random());
    }

    public static Translation randomWithLanguageCode(LanguageCode languageCode) {
        return create(
                languageCode.getValue(),
                SentenceMother.random(5)
        );
    }

    public static Translation randomWithNotLanguageCode(LanguageCode languageCode) {
        return create(
                LanguageCodeMother.randomNotIn(Collections.singletonList(languageCode)).getValue(),
                SentenceMother.random(5)
        );
    }

    public static List<Translation> randomList() {
        List<LanguageCode> languageCodes = LanguageCodeMother.randomList();
        return languageCodes.stream()
                .map(TranslationMother::randomWithLanguageCode)
                .collect(Collectors.toList());
    }

    public static List<Translation> randomListWithNotLanguageCode(LanguageCode languageCode) {
        List<LanguageCode> languageCodes = LanguageCodeMother.randomListWithoutLanguageCode(languageCode);
        return languageCodes.stream()
                .map(TranslationMother::randomWithLanguageCode)
                .collect(Collectors.toList());
    }

}
