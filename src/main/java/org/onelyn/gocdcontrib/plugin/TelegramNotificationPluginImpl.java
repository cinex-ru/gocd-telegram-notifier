package org.onelyn.gocdcontrib.plugin;

import com.google.gson.GsonBuilder;
import com.thoughtworks.go.plugin.api.GoApplicationAccessor;
import com.thoughtworks.go.plugin.api.GoPlugin;
import com.thoughtworks.go.plugin.api.GoPluginIdentifier;
import com.thoughtworks.go.plugin.api.annotation.Extension;
import com.thoughtworks.go.plugin.api.logging.Logger;
import com.thoughtworks.go.plugin.api.request.GoApiRequest;
import com.thoughtworks.go.plugin.api.request.GoPluginApiRequest;
import com.thoughtworks.go.plugin.api.response.GoApiResponse;
import com.thoughtworks.go.plugin.api.response.GoPluginApiResponse;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.onelyn.gocdcontrib.plugin.util.FieldValidator;
import org.onelyn.gocdcontrib.plugin.util.JSONUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Extension
public class TelegramNotificationPluginImpl implements GoPlugin {
    private static Logger LOGGER = Logger.getLoggerFor(TelegramNotificationPluginImpl.class);

    public static final String PLUGIN_ID = "telegram.notifier";
    public static final String EXTENSION_NAME = "notification";
    private static final List<String> goSupportedVersions = List.of("1.0");

    public static final String PLUGIN_SETTINGS_API_TOKEN = "api_token";
    public static final String PLUGIN_SETTINGS_CHAT_ID = "chat_id";

    public static final String PLUGIN_SETTINGS_GET_CONFIGURATION = "go.plugin-settings.get-configuration";
    public static final String PLUGIN_SETTINGS_GET_VIEW = "go.plugin-settings.get-view";
    public static final String PLUGIN_SETTINGS_VALIDATE_CONFIGURATION = "go.plugin-settings.validate-configuration";
    public static final String REQUEST_NOTIFICATIONS_INTERESTED_IN = "notifications-interested-in";
    public static final String REQUEST_STAGE_STATUS = "stage-status";

    public static final String GET_PLUGIN_SETTINGS = "go.processor.plugin-settings.get";

    public static final int SUCCESS_RESPONSE_CODE = 200;
    public static final int NOT_FOUND_RESPONSE_CODE = 404;
    public static final int INTERNAL_ERROR_RESPONSE_CODE = 500;

    private GoApplicationAccessor goApplicationAccessor;

    @Override
    public void initializeGoApplicationAccessor(GoApplicationAccessor goApplicationAccessor) {
        this.goApplicationAccessor = goApplicationAccessor;
    }

    @Override
    public GoPluginApiResponse handle(GoPluginApiRequest goPluginApiRequest) {
        String requestName = goPluginApiRequest.requestName();
        switch (requestName) {
            case PLUGIN_SETTINGS_GET_CONFIGURATION:
                return handleGetPluginSettingsConfiguration();
            case PLUGIN_SETTINGS_GET_VIEW:
                try {
                    return handleGetPluginSettingsView();
                } catch (IOException e) {
                    return renderJSON(500, String.format("Failed to find template: %s", e.getMessage()));
                }
            case PLUGIN_SETTINGS_VALIDATE_CONFIGURATION:
                return handleValidatePluginSettingsConfiguration(goPluginApiRequest);
            case REQUEST_NOTIFICATIONS_INTERESTED_IN:
                return handleNotificationsInterestedIn();
            case REQUEST_STAGE_STATUS:
                return handleStageNotification(goPluginApiRequest);
        }
        return renderJSON(NOT_FOUND_RESPONSE_CODE, null);
    }

    @Override
    public GoPluginIdentifier pluginIdentifier() {
        return getGoPluginIdentifier();
    }

    private GoPluginApiResponse handleStageNotification(GoPluginApiRequest goPluginApiRequest) {
        Map<String, Object> dataMap = (Map<String, Object>) JSONUtils.fromJSON(goPluginApiRequest.requestBody());

        PluginSettings pluginSettings = getPluginSettings();

        int responseCode = SUCCESS_RESPONSE_CODE;
        Map<String, Object> response = new HashMap<>();
        List<String> messages = new ArrayList<>();
        try {
            Map<String, Object> pipelineMap = (Map<String, Object>) dataMap.get("pipeline");
            Map<String, Object> stageMap = (Map<String, Object>) pipelineMap.get("stage");


            String pipelineName = (String) pipelineMap.get("name");
            String stageName = (String) stageMap.get("name");
            String stageState = (String) stageMap.get("state");

            String subject = String.format("<b>%s/%s is/has %s</b>\n\n", pipelineName, stageName, stageState);
            String body = String.format("State: %s\nResult: %s\nCreate Time: %s\nLast Transition Time: %s", stageState, stageMap.get("result"), stageMap.get("create-time"), stageMap.get("last-transition-time"));


            HttpClient httpclient = HttpClients.createDefault();
            HttpPost httppost = new HttpPost(String.format("https://api.telegram.org/bot%s/sendMessage", pluginSettings.getApiToken()));

            // Request parameters and other properties.
            List<NameValuePair> data = new ArrayList<>(2);
            data.add(new BasicNameValuePair("chat_id", "1514000389"));
            data.add(new BasicNameValuePair("text", subject + body));
            data.add(new BasicNameValuePair("parse_mode", "html"));
            httppost.setEntity(new UrlEncodedFormEntity(data, "UTF-8"));

            //Execute and get the response.
            HttpResponse httpResponse = httpclient.execute(httppost);
            HttpEntity entity = httpResponse.getEntity();

            if (entity != null) {
                try (InputStream instream = entity.getContent()) {
                    Reader reader = new InputStreamReader(instream, StandardCharsets.UTF_8);
                    Map<String, Object> content = (Map<String, Object>) new GsonBuilder().create().fromJson(reader, Object.class);

                    boolean result = (boolean) content.getOrDefault("ok", false);
                    if (result) {
                        response.put("status", "success");
                    } else {
                        responseCode = INTERNAL_ERROR_RESPONSE_CODE;
                        response.put("status", "failure");
                        String description = (String) content.getOrDefault("description", "");
                        if (!description.isEmpty()) {
                            messages.add(description);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Error occurred while trying to deliver an email.", e);

            responseCode = INTERNAL_ERROR_RESPONSE_CODE;
            response.put("status", "failure");
            if (!isEmpty(e.getMessage())) {
                messages.add(e.getMessage());
            }
        }

        if (!messages.isEmpty()) {
            response.put("messages", messages);
        }
        return renderJSON(responseCode, response);
    }


    private GoPluginApiResponse handleNotificationsInterestedIn() {
        Map<String, Object> response = new HashMap<>();
        response.put("notifications", Arrays.asList(REQUEST_STAGE_STATUS));
        return renderJSON(SUCCESS_RESPONSE_CODE, response);
    }

    private GoPluginApiResponse handleGetPluginSettingsConfiguration() {
        Map<String, Object> response = new HashMap<>();
        response.put(PLUGIN_SETTINGS_API_TOKEN, createField("API token", null, true, false, "0"));
        response.put(PLUGIN_SETTINGS_CHAT_ID, createField("Chat ID", null, true, false, "1"));
        return renderJSON(SUCCESS_RESPONSE_CODE, response);
    }

    private Map<String, Object> createField(String displayName, String defaultValue, boolean isRequired, boolean isSecure, String displayOrder) {
        Map<String, Object> fieldProperties = new HashMap<>();
        fieldProperties.put("display-name", displayName);
        fieldProperties.put("default-value", defaultValue);
        fieldProperties.put("required", isRequired);
        fieldProperties.put("secure", isSecure);
        fieldProperties.put("display-order", displayOrder);
        return fieldProperties;
    }

    private GoPluginApiResponse handleGetPluginSettingsView() throws IOException {
        Map<String, Object> response = new HashMap<>();
        response.put("template", IOUtils.toString(getClass().getResourceAsStream("/plugin-settings.template.html"), StandardCharsets.UTF_8));
        return renderJSON(SUCCESS_RESPONSE_CODE, response);
    }
    private void validate(List<Map<String, Object>> response, FieldValidator fieldValidator) {
        Map<String, Object> fieldValidation = new HashMap<>();
        fieldValidator.validate(fieldValidation);
        if (!fieldValidation.isEmpty()) {
            response.add(fieldValidation);
        }
    }

    private GoPluginApiResponse handleValidatePluginSettingsConfiguration(GoPluginApiRequest goPluginApiRequest) {
        Map<String, Object> responseMap = (Map<String, Object>) JSONUtils.fromJSON(goPluginApiRequest.requestBody());
        final Map<String, String> configuration = keyValuePairs(responseMap, "plugin-settings");
        List<Map<String, Object>> response = new ArrayList<>();

        validate(response, fieldValidation -> validateRequiredField(configuration, fieldValidation, PLUGIN_SETTINGS_API_TOKEN, "API token"));

        validate(response, fieldValidation -> validateRequiredField(configuration, fieldValidation, PLUGIN_SETTINGS_CHAT_ID, "Chat ID"));

        return renderJSON(SUCCESS_RESPONSE_CODE, response);
    }

    private void validateRequiredField(Map<String, String> configuration, Map<String, Object> fieldMap, String key, String name) {
        if (configuration.get(key) == null || configuration.get(key).isEmpty()) {
            fieldMap.put("key", key);
            fieldMap.put("message", String.format("'%s' is a required field", name));
        }
    }

    public PluginSettings getPluginSettings() {
        Map<String, Object> requestMap = new HashMap<>();
        requestMap.put("plugin-id", PLUGIN_ID);
        GoApiResponse response = goApplicationAccessor.submit(createGoApiRequest(GET_PLUGIN_SETTINGS, JSONUtils.toJSON(requestMap)));
        if (response.responseBody() == null || response.responseBody().trim().isEmpty()) {
            throw new RuntimeException("plugin is not configured. please provide plugin settings.");
        }
        Map<String, String> responseBodyMap = (Map<String, String>) JSONUtils.fromJSON(response.responseBody());
        return new PluginSettings(responseBodyMap.get(PLUGIN_SETTINGS_API_TOKEN), responseBodyMap.get(PLUGIN_SETTINGS_CHAT_ID));
    }

    private boolean isEmpty(String str) {
        return str == null || str.trim().isEmpty();
    }

    private Map<String, String> keyValuePairs(Map<String, Object> map, String mainKey) {
        Map<String, String> keyValuePairs = new HashMap<>();
        Map<String, Object> fieldsMap = (Map<String, Object>) map.get(mainKey);
        for (String field : fieldsMap.keySet()) {
            Map<String, Object> fieldProperties = (Map<String, Object>) fieldsMap.get(field);
            String value = (String) fieldProperties.get("value");
            keyValuePairs.put(field, value);
        }
        return keyValuePairs;
    }
    private GoPluginIdentifier getGoPluginIdentifier() {
        return new GoPluginIdentifier(EXTENSION_NAME, goSupportedVersions);
    }

    private GoApiRequest createGoApiRequest(final String api, final String responseBody) {
        return new GoApiRequest() {
            @Override
            public String api() {
                return api;
            }

            @Override
            public String apiVersion() {
                return "1.0";
            }

            @Override
            public GoPluginIdentifier pluginIdentifier() {
                return getGoPluginIdentifier();
            }

            @Override
            public Map<String, String> requestParameters() {
                return null;
            }

            @Override
            public Map<String, String> requestHeaders() {
                return null;
            }

            @Override
            public String requestBody() {
                return responseBody;
            }
        };
    }

    private GoPluginApiResponse renderJSON(final int responseCode, Object response) {
        final String json = response == null ? null : new GsonBuilder().create().toJson(response);
        return new GoPluginApiResponse() {
            @Override
            public int responseCode() {
                return responseCode;
            }

            @Override
            public Map<String, String> responseHeaders() {
                return null;
            }

            @Override
            public String responseBody() {
                return json;
            }
        };
    }
}
