package com.nexus_flow.core.domain;

import com.nexus_flow.core.ddd.value_objects.LanguageCode;
import com.nexus_flow.core.ddd.value_objects.Translatable;
import com.nexus_flow.core.ddd.value_objects.TranslatableCode;
import com.nexus_flow.core.ddd.value_objects.Translation;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.stream.Collectors.toMap;

public final class TranslatableMother {

    public static Translatable create(TranslatableCode code, Translation translation) {
        return new Translatable(code, translation);
    }

    public static Translatable create(TranslatableCode code, List<Translation> translation) {
        return new Translatable(code, translation);
    }

    public static Translatable createWithLanguagesAndEmptyTranslations(Set<LanguageCode> languages) {
        return create(
                TranslatableCodeMother.random(),
                Translation.listBuilder(
                        languages.stream().collect(
                                toMap(
                                        LanguageCode::getValue,
                                        translation -> ""
                                )
                        )
                )
        );
    }

    public static Translatable createWithCodeAndLanguagesAndRandomTranslations(TranslatableCode code,
                                                                               Set<LanguageCode> languages) {
        return create(
                code,
                Translation.listBuilder(
                        languages.stream().collect(
                                toMap(
                                        LanguageCode::getValue,
                                        translation -> SentenceMother.random(5)
                                )
                        )
                )
        );
    }

    public static Translatable random() {
        return create(TranslatableCodeMother.random(), TranslationMother.random());
    }

    public static Map<String, String> randomPrimitives() {
        return TranslatableMother.random()
                .getTranslations()
                .stream()
                .collect(toMap(translation -> translation.getLanguageCode().getValue(), Translation::getValue));
    }

}
