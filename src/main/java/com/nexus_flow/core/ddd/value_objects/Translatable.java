package com.nexus_flow.core.ddd.value_objects;

import com.nexus_flow.core.ddd.Utils;
import com.nexus_flow.core.ddd.exceptions.WrongFormat;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

public class Translatable {

    protected TranslatableCode  code;
    protected List<Translation> translations;

    protected Translatable() {
    }

    public Translatable(TranslatableCode code,
                        Translation translation) {
        this.code         = code;
        this.translations = new ArrayList<>();
        this.translations.add(translation);
    }

    public Translatable(TranslatableCode code,
                        List<Translation> translations) {
        checkNotRepeatedLanguages(code, translations);
        this.code         = code;
        this.translations = translations;
    }

    public Translatable(TranslatableCode code,
                        List<Translation> translations,
                        Integer maxLength) {
        checkNotRepeatedLanguages(code, translations);
        ensureNotExceedMaxLengthInTranslations(translations, maxLength, code.getValue());
        this.code         = code;
        this.translations = translations;
    }

    private void checkNotRepeatedLanguages(TranslatableCode code,
                                           List<Translation> translations) {
        if (!translations.stream().map(Translation::getLanguageCode).allMatch(new HashSet<>()::add)) {
            throw new WrongFormat("Can't exist duplicated languages codes in translations (" + code.getValue() + ")");
        }
    }

    public void addLanguage(LanguageCode language) {
        if (translations.stream().noneMatch(t -> t.getLanguageCode().equals(language))) {
            translations.add(new Translation(language));
        }
    }

    public void addLanguages(Set<LanguageCode> languages) {
        languages.forEach(this::addLanguage);
    }

    public void emptyLanguages(Set<LanguageCode> languages) {
        replace(languages.stream().map(Translation::new).collect(Collectors.toSet()));
    }

    public void replace(Translation newTranslation) {
        this.remove(newTranslation.getLanguageCode());
        this.translations.add(newTranslation);
    }

    public void replace(Set<Translation> translations) {
        translations.forEach(this::replace);
    }

    public void remove(LanguageCode languageCode) {
        this.translations.removeIf(currentTranslation -> currentTranslation.getLanguageCode().equals(languageCode));
    }

    public void remove(Set<LanguageCode> translations) {
        translations.forEach(this::remove);
    }

    public void clean() {
        translations.forEach(Translation::clean);
    }

    public TranslatableCode getCode() {
        return code;
    }

    public List<LanguageCode> allLanguages() {
        return translations.stream().map(Translation::getLanguageCode).collect(Collectors.toList());
    }

    public List<Translation> getTranslations() {
        return translations;
    }

    public Map<String, Serializable> translationsAsMap() {
        return translations.stream()
                .collect(Collectors.toMap(translation -> translation.getLanguageCode().getValue(),
                                          Translation::getValue));

    }

    public Translation translationIn(LanguageCode languageCode) {
        return translations.stream()
                .filter(x -> x.getLanguageCode().equals(languageCode))
                .findFirst()
                .orElse(new Translation(languageCode, new TranslationValue("")));
    }

    public Set<Translation> translationNotIn(LanguageCode languageCode) {
        return translations.stream()
                .filter(x -> !x.getLanguageCode().equals(languageCode))
                .collect(Collectors.toSet());
    }

    public void ensureNotExceedMaxLengthInTranslations(List<Translation> translations,
                                                       Integer maxLength,
                                                       String fieldCode) {
        boolean anyMatch = translations.stream().anyMatch(t -> t.getValue().length() > maxLength);
        if (anyMatch) {
            throw new WrongFormat("Some translation for field " +
                                  Utils.toCamelFirstLower(fieldCode) +
                                  " exceed max length");
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, translations);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Translatable that = (Translatable) o;
        return Objects.equals(code, that.code) &&
               Objects.equals(translations, that.translations);
    }
}
