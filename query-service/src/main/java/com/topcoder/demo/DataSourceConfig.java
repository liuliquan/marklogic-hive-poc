package com.topcoder.demo;

import java.io.IOException;
import java.util.Properties;

import javax.sql.DataSource;

import org.apache.commons.dbcp2.BasicDataSource;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DataSourceConfig {

    @Value("${marklogic.username}")
    private String marklogicUsername;

    @Value("${marklogic.password}")
    private String marklogicPassword;

    @Value("${hive.jdbc.url}")
    private String hiveJdbcUrl;

    @Value("${hive.jdbc.username}")
    private String hiveJdbcUsername;

    @Value("${hive.jdbc.password}")
    private String hiveJdbcPassword;

    @Bean
    public CloseableHttpClient markLogicClient() {
        CredentialsProvider credsProvider = new BasicCredentialsProvider();
        credsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(marklogicUsername, marklogicPassword));

        CloseableHttpClient httpclient = HttpClients.custom().setDefaultCredentialsProvider(credsProvider).build();
        return httpclient;
    }

    @Bean
    public DataSource hiveDataSource() {
        BasicDataSource ds = new BasicDataSource();

        ds.setDriverClassName("org.apache.hive.jdbc.HiveDriver");
        ds.setUrl(hiveJdbcUrl);
        ds.setUsername(hiveJdbcUsername);
        ds.setPassword(hiveJdbcPassword);

        return ds;
    }

    @Bean
    public Properties columnsProperties() throws IOException {
        Properties props = new Properties();
        props.load(DataSourceConfig.class.getResourceAsStream("/columns.properties"));
        return props;
    }
}
