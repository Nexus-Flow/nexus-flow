package com.nexus_flow.core.configurations.elasticsearch;


import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.SneakyThrows;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.ssl.SSLContexts;
import org.elasticsearch.client.RestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.SSLContext;
import java.io.InputStream;
import java.security.KeyStore;
import java.time.ZonedDateTime;

@Configuration
public class ElasticSearchConfig {

    @Bean
    @SneakyThrows
    public ElasticsearchClient elasticsearchClient(ElasticSearchProperties elasticSearchProperties,
                                                   NexusFlowInstantDeserializer nexusFlowInstantDeserializer) {

        CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(elasticSearchProperties.getUserName(), elasticSearchProperties.getPassword()));

        InputStream inputStream = getClass().getResourceAsStream(elasticSearchProperties.getKeystoreLocation());
        KeyStore    trustStore  = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(inputStream, elasticSearchProperties.getKeystorePassword().toCharArray());
        SSLContext sslContext = SSLContexts.custom().loadTrustMaterial(trustStore, new TrustSelfSignedStrategy()).build();

        HttpHost   httpHost   = new HttpHost(elasticSearchProperties.getHost(), Integer.parseInt(elasticSearchProperties.getPort()), "https");
        RestClient restClient = RestClient.builder(httpHost).setHttpClientConfigCallback(httpClientBuilder -> httpClientBuilder.setSSLContext(sslContext)).build();

        // Creates transport with our own mapper for dates
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.registerModule(new JavaTimeModule().addDeserializer(ZonedDateTime.class, nexusFlowInstantDeserializer));

        JacksonJsonpMapper     jacksonJsonpMapper = new JacksonJsonpMapper(objectMapper);
        ElasticsearchTransport transport          = new RestClientTransport(restClient, jacksonJsonpMapper);

        // Creates the API client
        return new ElasticsearchClient(transport);
    }

}
