package com.nexus_flow.core.configurations.qualifier_resolver;

import org.springframework.beans.TypeConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.QualifierAnnotationAutowireCandidateResolver;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.stereotype.Component;

import java.lang.annotation.Annotation;

@Component
public class QualifierWithPlaceholderResolverConfigurer implements BeanFactoryPostProcessor {

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        DefaultListableBeanFactory bf = (DefaultListableBeanFactory) beanFactory;
        bf.setAutowireCandidateResolver(new WithPlaceholderResolverQualifierAnnotationAutowireCandidateResolver());
    }

    private static class WithPlaceholderResolverQualifierAnnotationAutowireCandidateResolver extends QualifierAnnotationAutowireCandidateResolver {

        @Override
        protected boolean checkQualifier(BeanDefinitionHolder bdHolder, Annotation annotation, TypeConverter typeConverter) {
            if (annotation instanceof Qualifier) {
                Qualifier qualifier = (Qualifier) annotation;
                if (qualifier.value().contains("${") && qualifier.value().contains("}")) {

                    String value = qualifier.value();

                    int    initPlaceholder = value.indexOf("${");
                    int    endPlaceholder  = value.indexOf("}");
                    String placeholder     = value.substring(initPlaceholder, endPlaceholder + 1);

                    DefaultListableBeanFactory bf = (DefaultListableBeanFactory) this.getBeanFactory();
                    assert bf != null;

                    String resolvedPlaceholder = bf.resolveEmbeddedValue(placeholder);
                    assert resolvedPlaceholder != null;

                    String wholeValue = value.substring(0, Math.max(initPlaceholder, 0)) +
                            resolvedPlaceholder +
                            value.substring(Math.min(endPlaceholder+1, value.length()));

                    ResolvedQualifier resolvedQualifier = new ResolvedQualifier(wholeValue);

                    return super.checkQualifier(bdHolder, resolvedQualifier, typeConverter);
                }
            }
            return super.checkQualifier(bdHolder, annotation, typeConverter);
        }

        private static class ResolvedQualifier implements Qualifier {

            private final String value;

            ResolvedQualifier(String value) {
                this.value = value;
            }

            @Override
            public String value() {
                return this.value;
            }

            @Override
            public Class<? extends Annotation> annotationType() {
                return Qualifier.class;
            }

        }

    }
}
