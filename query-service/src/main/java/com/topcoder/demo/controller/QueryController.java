package com.topcoder.demo.controller;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;

import org.apache.commons.fileupload.MultipartStream;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.json.JSONObject;
import org.json.XML;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.fasterxml.jackson.databind.ObjectMapper;

@Controller
public class QueryController {
    private final static Logger logger = LoggerFactory.getLogger(QueryController.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${marklogic.url}")
    private String marklogicUrl;

    @Autowired
    private CloseableHttpClient markLogicClient;

    @Autowired
    private DataSource hiveDataSource;

    @Autowired
    private Properties columnsProperties;

    @GetMapping(path = "/query", produces = { MediaType.APPLICATION_JSON_UTF8_VALUE, MediaType.APPLICATION_XML_VALUE })
    public void queryData(@RequestParam(required = false) String xquery, @RequestParam(required = false) String sql,
            @RequestParam(required = false, defaultValue = "json") String format, HttpServletResponse response)
            throws IOException, SQLException {

        if (StringUtils.isBlank(xquery) && StringUtils.isBlank(sql)) {
            response.sendError(400, "Either xquery or sql should be present");
            return;
        }

        boolean outputJson = "json".equalsIgnoreCase(format);
        if (!outputJson && !"xml".equalsIgnoreCase(format)) {
            response.sendError(400, "Only xml/json format are supported");
            return;
        }

        OutputStream output = response.getOutputStream();
        if (outputJson) {
            response.setHeader("Content-Type", "application/json");
            output.write("{".getBytes());
        } else {
            response.setHeader("Content-Type", "application/xml");
            output.write("<response>".getBytes());
        }

        queryMarkLogic(output, outputJson, xquery, sql);
        if (!StringUtils.isBlank(sql)) {
            if (outputJson) {
                output.write(",".getBytes());
            }
            queryHive(output, outputJson, sql);
        }

        if (outputJson) {
            output.write("}".getBytes());
        } else {
            output.write("</response>".getBytes());
        }
        output.flush();
    }

    private void queryHive(OutputStream output, boolean outputJson, String sql) throws SQLException, IOException {
        try (Connection conn = hiveDataSource.getConnection(); Statement stmt = conn.createStatement();) {
            long timestamp = System.currentTimeMillis();

            if (outputJson) {
                output.write("\"hive\":".getBytes());
            } else {
                output.write("<hive>".getBytes());
            }

            ResultSet rs = null;
            try {
                rs = stmt.executeQuery(sql);
                logger.info("Query Hive get response in " + (System.currentTimeMillis() - timestamp) + "ms");
                timestamp = System.currentTimeMillis();
            } catch (SQLException e) {
                logger.error("Failed to query hive", e);
                if (outputJson) {
                    output.write("{\"error\":\"Failed to query hive\"}".getBytes());
                } else {
                    output.write("<error>Failed to query hive</error>".getBytes());
                }
            }

            if (rs != null) {

                ResultSetMetaData metadata = rs.getMetaData();
                int count = metadata.getColumnCount();
                String[] columnNames = new String[count];
                for (int i = 1; i <= count; i++) {
                    columnNames[i - 1] = metadata.getColumnName(i);
                    String[] split = columnNames[i - 1].split("\\.");
                    columnNames[i - 1] = split[split.length - 1];
                    columnNames[i - 1] = columnsProperties.getProperty(columnNames[i - 1], columnNames[i - 1]);
                    columnNames[i - 1] = outputJson ? StringEscapeUtils.escapeJson(columnNames[i - 1])
                            : StringEscapeUtils.escapeXml10(columnNames[i - 1]);
                }

                if (outputJson) {
                    output.write("[".getBytes());
                }
                boolean firstRow = true;

                long rowCount = 0;
                // Iterate each result row
                while (rs.next()) {
                    if (outputJson) {
                        if (firstRow) {
                            firstRow = false;
                        } else {
                            output.write(",".getBytes());
                        }
                    } else {
                        output.write("<row>".getBytes());
                    }

                    if (outputJson) {
                        output.write("{".getBytes());
                        for (int i = 1; i <= count; i++) {
                            String value = StringEscapeUtils.escapeJson(rs.getString(i));
                            if (value != null) {
                                value = "\"" + value + "\"";
                            }
                            output.write(("\"" + columnNames[i - 1] + "\":" + value).getBytes());
                            if (i != count) {
                                output.write(",".getBytes());
                            }
                        }
                        output.write("}".getBytes());
                    } else {
                        for (int i = 1; i <= count; i++) {
                            String value = StringEscapeUtils.escapeXml10(rs.getString(i));
                            output.write(("<" + columnNames[i - 1] + ">" + value + "</" + columnNames[i - 1] + ">")
                                    .getBytes());
                        }
                    }

                    if (!outputJson) {
                        output.write("</row>".getBytes());
                    }
                    rowCount++;
                }

                if (outputJson) {
                    output.write("]".getBytes());
                }
                logger.info("Query Hive transfer {} records in {} ms", rowCount, System.currentTimeMillis() - timestamp);
            }

            if (!outputJson) {
                output.write("</hive>".getBytes());
            }
        }
    }

    private void queryMarkLogic(OutputStream output, boolean outputJson, String xquery, String sql) throws IOException {

        String adhocXquery = !StringUtils.isBlank(xquery) ? xquery
                : "xquery version \"1.0-ml\";xdmp:sql(\"" + sql + "\")";

        HttpPost post = new HttpPost(marklogicUrl);
        post.setHeader("Content-type", "application/x-www-form-urlencoded");
        post.setHeader("Accept", "multipart/mixed; boundary=BOUNDARY");
        post.setEntity(new StringEntity("xquery=" + adhocXquery));

        if (outputJson) {
            output.write("\"marklogic\":".getBytes());
        } else {
            output.write("<marklogic>".getBytes());
        }

        long timestamp = System.currentTimeMillis();
        try (CloseableHttpResponse mlresponse = markLogicClient.execute(post)) {
            logger.info("Query MarkLogic get response in " + (System.currentTimeMillis() - timestamp) + "ms");
            timestamp = System.currentTimeMillis();

            HttpEntity entity = mlresponse.getEntity();

            try {
                int status = mlresponse.getStatusLine().getStatusCode();
                if (status != 200) {
                    logger.error("Failed to query marklogic:\n" + EntityUtils.toString(entity));
                    if (outputJson) {
                        output.write("{\"error\":\"Failed to query marklogic\"}".getBytes());
                    } else {
                        output.write("<error>Failed to query marklogic</error>".getBytes());
                    }
                } else {
                    try (InputStream is = entity.getContent()) {
                        // Parse multipart stream
                        MultipartStream multipartStream = new MultipartStream(is, "BOUNDARY".getBytes(), 10000, null);

                        if (outputJson) {
                            output.write("[".getBytes());
                        }

                        long rowCount = 0;
                        boolean firstRow = true;
                        String[] headerArray = null;

                        // Iterate each part
                        boolean nextPart = multipartStream.skipPreamble();
                        while (nextPart) {
                            Map<String, String> partHeaders = parseHeaders(multipartStream.readHeaders());

                            String partType = partHeaders.get("Content-Type");
                            String partPrimitive = partHeaders.get("X-Primitive");

                            boolean dataRow = !isJSONType(partType) || !"header-array".equalsIgnoreCase(partPrimitive);
                            if (dataRow) {
                                rowCount++;
                                if (outputJson) {
                                    if (firstRow) {
                                        firstRow = false;
                                    } else {
                                        output.write(",".getBytes());
                                    }
                                } else {
                                    output.write("<row>".getBytes());
                                }
                            }

                            byte[] partBytes = readBodyData(multipartStream);

                            if (isJSONType(partType)) {
                                // Handle json part

                                if ("header-array".equalsIgnoreCase(partPrimitive)) {
                                    // This represents table header columns
                                    headerArray = MAPPER.readValue(partBytes, String[].class);
                                    for (int i = 0; i < headerArray.length; i++) {
                                        String[] split = headerArray[i].split("\\.");
                                        headerArray[i] = split[split.length - 1];
                                        if (outputJson) {
                                            headerArray[i] = StringEscapeUtils.escapeJson(headerArray[i]);
                                        } else {
                                            headerArray[i] = StringEscapeUtils.escapeXml10(headerArray[i]);
                                        }
                                    }
                                } else if ("row-array".equalsIgnoreCase(partPrimitive)) {
                                    // This represents table row values
                                    String[] rowArray = MAPPER.readValue(partBytes, String[].class);

                                    if (outputJson) {
                                        output.write("{".getBytes());
                                        for (int i = 0; i < headerArray.length; i++) {
                                            String value = StringEscapeUtils.escapeJson(rowArray[i]);
                                            if (value != null) {
                                                value = "\"" + value + "\"";
                                            }
                                            output.write(("\"" + headerArray[i] + "\":" + value).getBytes());
                                            if (i != headerArray.length - 1) {
                                                output.write(",".getBytes());
                                            }
                                        }
                                        output.write("}".getBytes());
                                    } else {
                                        for (int i = 0; i < headerArray.length; i++) {
                                            String value = StringEscapeUtils.escapeXml10(rowArray[i]);
                                            output.write(
                                                    ("<" + headerArray[i] + ">" + value + "</" + headerArray[i] + ">")
                                                            .getBytes());
                                        }
                                    }
                                } else {
                                    // Other primitive of json part
                                    if (outputJson) {
                                        output.write(partBytes);
                                    } else {
                                        output.write(jsonToXml(partBytes));
                                    }
                                }
                            } else if (isXMLType(partType)) {
                                // Handle xml part
                                if (outputJson) {
                                    output.write(xmlToJson(partBytes));
                                } else {
                                    output.write(partBytes);
                                }
                            } else {
                                // Handle unknown part type
                                if (outputJson) {
                                    output.write(("\"" + StringEscapeUtils.escapeJson(new String(partBytes)) + "\"")
                                            .getBytes());
                                } else {
                                    output.write(StringEscapeUtils.escapeXml10(new String(partBytes)).getBytes());
                                }
                            }

                            if (dataRow && !outputJson) {
                                output.write("</row>".getBytes());
                            }

                            output.flush();
                            nextPart = multipartStream.readBoundary();
                        }

                        if (outputJson) {
                            output.write("]".getBytes());
                        }

                        logger.info("Query MarkLogic transfer {} records in {} ms", rowCount, System.currentTimeMillis() - timestamp);
                    }
                }

                if (!outputJson) {
                    output.write("</marklogic>".getBytes());
                }

            } finally {
                EntityUtils.consume(entity);
            }
        }
    }

    private static byte[] jsonToXml(byte[] json) {
        String jsonStr = new String(json).trim();
        if (jsonStr.startsWith("[")) {
            jsonStr = "{\"array\":" + jsonStr + "}";
        } else if (!jsonStr.startsWith("{")) {
            jsonStr = "{\"data\":" + jsonStr + "}";
        }
        JSONObject jsonObj = new JSONObject(jsonStr);
        return XML.toString(jsonObj).getBytes();
    }

    private static byte[] xmlToJson(byte[] xml) {
        JSONObject json = XML.toJSONObject(new String(xml).trim());
        return json.toString().getBytes();
    }

    private static boolean isJSONType(String mimeType) {
        return mimeType.startsWith("application/json");
    }

    private static boolean isXMLType(String mimeType) {
        return mimeType.startsWith("application/xml") || mimeType.startsWith("text/xml")
                || mimeType.startsWith("text/html");
    }

    private static byte[] readBodyData(MultipartStream multipartStream) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        multipartStream.readBodyData(baos);
        return baos.toByteArray();
    }

    private static Map<String, String> parseHeaders(String headers) {
        Map<String, String> result = new HashMap<>();
        String[] lines = headers.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] headerRow = line.split(":");
            result.put(headerRow[0].trim(), headerRow[1].trim());
        }
        return result;
    }
}