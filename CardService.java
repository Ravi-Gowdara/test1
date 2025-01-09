package com.pagerduty.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;


import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.ciscospark.webexbotservice.webexbot.entity.BotLink;
import com.ciscospark.webexbotservice.webexbot.entity.BotMembership;
import com.ciscospark.webexbotservice.webexbot.entity.Bots;
import com.ciscospark.webexbotservice.webexbot.model.Message;
import com.ciscospark.webexbotservice.webexbot.repository.BotLinkRepository;
import com.ciscospark.webexbotservice.webexbot.service.BotsService;
import com.ciscospark.webexbotservice.webexbot.util.Util;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import com.ciscospark.webexbotservice.webexbot.model.Person;
import  com.ciscospark.webexbotservice.webexbot.service.BotMembershipService;
import  com.ciscospark.webexbotservice.webexbot.service.WebexService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pagerduty.model.IncidentCreated;
import com.pagerduty.model.Oncall;
import com.pagerduty.model.PagerUser;
import com.pagerduty.model.Services;
import com.pagerduty.util.BotUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/**
 * CardService handles all the card related requests.
 *
 * <p>this class is responsible creating all types of cards:
 * {@link #readCardJson(String)}, {@link #createCardJson()},
 * {@link #prepareHelpCard(String, String, String, String,String)},{@link #prepareLinkCard(String,String,String,String,String)},
 * </p>
 */

@Service
public class CardService {

    private static final Logger logger = LoggerFactory.getLogger(CardService.class);
    private PagerDutyAPIService pagerDutyAPIService;
    private WebexService webexService;
    private BotMembershipService botMembershipService;

    @Autowired
    private BotLinkRepository botLinkRepository;
    @Autowired
    private BotsService botsService;
    @Value("${frontend.url}")
    private String frontendUrl;

    public CardService(PagerDutyAPIService pagerDutyAPIService,WebexService webexService,BotMembershipService botMembershipService) {
        this.pagerDutyAPIService = pagerDutyAPIService;
        this.webexService=webexService;
        this.botMembershipService=botMembershipService;
    }

    public String readCardJson(String cardName) {
        Resource resource = new ClassPathResource("cards/" + cardName + ".json");
        try {
            return new String(Files.readAllBytes(Paths.get(resource.getURI())));
        } catch (IOException e) {
            throw new RuntimeException("Failed to read card.json", e);
        }
    }

    public String createCardJson() {
        Resource resource = new ClassPathResource("cards/createIncidentCard.json");
        try {
            return new String(Files.readAllBytes(Paths.get(resource.getURI())));
        } catch (IOException e) {
            throw new RuntimeException("Failed to read createIncidentCard.json", e);
        }
    }

/**
** Prepares help card whenever the given command is help.
*
* @param botName name of the bot
* @param userId id of the user who sends the help command
* @param roomId id of the room to which the message to be sent
* @param roomType type of the room to which the message to be sent
*
* @return A String indicating prepared Help Card!
* @throws IOException If an I/O error occurs during the operation.
* @throws InterruptedException If the operation is interrupted.
 * @throws JSONException
*/
public String prepareHelpCard(String botName, String userId, String roomId, String roomType,String botToken) throws IOException, InterruptedException, JSONException {
        //Creating Instance of Object Mapper
        ObjectMapper objectMapper = new ObjectMapper();
        // Step 2: Get the serviceId using spaceId/roomId
        String botId=botsService.findBotIdByBotName(botName);
        String serviceId = botMembershipService.getCustomDataIdByBotIdAndSpaceId(roomId,botId);

        // Step 3: Get the service Details using User and serviceId

        JsonNode serviceDetail = pagerDutyAPIService.getServiceDetail(botName, userId, serviceId); //Getting service Details from API
        if (serviceDetail != null) {
            if (serviceDetail.has("responseStatus") && serviceDetail.get("responseStatus").asText().equals("unauthorized")) {
                String linkCardJson = prepareLinkCard(userId, roomId, botName, botToken, "relink");
                webexService.sendDirectMessage(userId, linkCardJson, botToken, true);
            }else {
                String serviceName = serviceDetail.get("service").get("name").asText(); //Extracting the ServiceName from serviceDetail

                // Step 4: Get escalationPolicyName using service Detail
                String escalationPolicyId = serviceDetail.get("service").get("escalation_policy").get("id").asText();//Extracting the Escalation Policy Id from serviceDetail
                JsonNode escalationPolicyDetail = pagerDutyAPIService.getEscalationPolicy(botName, userId, escalationPolicyId); //Getting EscalationPolicy Details by passing escalationPolicyId, from API
                if (escalationPolicyDetail != null) {
                    if (escalationPolicyDetail.has("responseStatus") && escalationPolicyDetail.get("responseStatus").asText().equals("unauthorized")) {
                        String linkCardJson = prepareLinkCard(userId, roomId, botName, botToken, "relink");
                        webexService.sendDirectMessage(userId, linkCardJson, botToken, true);
                    } else {
                        String escalationPolicyName = escalationPolicyDetail.get("escalation_policy").get("name").asText(); //Extracting escalationPolicyName from escalationPolicyDetail

        // Step 5: Read Json files of helpCard and createIncidentCard
        String helpCard  = readCardJson("helpCard"); //Getting help Card
        String createIncidentCard = readCardJson("createIncidentCard"); //Getting createIncident Card
        List<Map<String, String>> priorityList = pagerDutyAPIService.getPriorities(botName,userId);
        if(priorityList!=null){
        createIncidentCard = updatePriorities(createIncidentCard, priorityList);
        }else{
            String linkCardJson = prepareLinkCard(userId,roomId,botName,botToken,"relink");
            webexService.sendDirectMessage(userId,linkCardJson,botToken,true);
        }
        // Step 6: getting body of createIncidentCard to make it subCard
        createIncidentCard = objectMapper.writeValueAsString(objectMapper.readTree(createIncidentCard).get("body"));

                        // Step 7: Set visibility, Impacted Service, Escalation Policy and deleteFlag values in the createIncidentCard String
                        String impactedService = "Impacted Service: " + "**" + serviceName + "**";
                        String escalationPolicy = "Escalation Policy: " + "**" + escalationPolicyName + "**";
                        String createIncidentString = createIncidentCard.replace("\"visibility\"", String.valueOf(false)) //Setting visibility to false
                                .replace("Impacted Service:", impactedService) // Setting impactedService
                                .replace("Escalation Policy:", escalationPolicy) //Setting escalationPolicy
                                .replace("\"deleteFlag\"", String.valueOf(false)); //Setting deleteFlag to false

                        // Step 8: Set replaceString as @pagerDuty if roomType is group
                        if (roomType == null) {
                            roomType = "";
                            helpCard = helpCard.replace("Hello again! 👋 ", "You've successfully linked to PagerDuty 🙌");
                        }
                        String replaceString = "";
                        if (roomType.equals("group")) {
                            logger.info("ADDING PAGERDUTY TO GROUP");
                            replaceString = "@PagerDuty";
                        }

        // Step 9:
        /*
         * Add choices, placeholderService, replaceString to the helpCard String
         * and insert createIncidentCardString in between helpCard
         * */
        String message = helpCard.
                replace("\"createIncidentCard\"", createIncidentString)
                .replaceAll("\\{tag\\}", replaceString)
                .replace("\"PlaceHolderService\"", "\"" + serviceName + "\"");

                        // Step 10: returning the complete HelpCard String
                        return message;
                    }
                } else {
                    logger.info("Error fetching escalation policy");
                }
            }
        } else {
            logger.info("Error fetching service details");
        }

        return "";
    }
    public String prepareLinkCard(String actorId, String roomId, String botName,String botToken,String linkType) throws IOException, InterruptedException {
        LocalDateTime currentDateTime = LocalDateTime.now();
        String encryptedUserString = "";
        try {
            String botId = botsService.findBotIdByBotName(botName);
            encryptedUserString = Util.encryptUrl(actorId + "$" + botId+"$"+currentDateTime);
            Bots bot = botsService.getBotByName(botName);
            BotMembership botMembership = botMembershipService.getBotMembershipBySpaceIdAndBot(roomId, bot);
            BotLink botLink = new BotLink();
            botLink.setCreatedAt(currentDateTime);
            botLink.setUniqueLink(encryptedUserString);
            botLink.setUserId(actorId);
            botLink.setLinkOpened(false);
            botLink.setBotMembership(botMembership);
            botLinkRepository.save(botLink);
        } catch (JsonProcessingException e) {
            logger.error("Error in generateCodeChallenge: ", e);
        } catch (Exception e) {
            logger.error("Error in generateCodeChallenge: ", e);
        }
        String message = frontendUrl+"/login?token=" + encryptedUserString+"&action=link-account";
        switch (linkType){
            case "link":
                return readCardJson("WelcomeCard").replace("loginUrl", message);
            case "relink":
                Person person=webexService.getPerson(botToken,actorId);
                String name=person.getDisplayName();
                int ind=name.indexOf(" ");
                if(ind!=-1){
                    name=name.substring(0,ind);
                }
                return readCardJson("relinkCard").replace("loginUrl", message).replace("${username}",name);
        }
        return "";
    }
    public String getUpdatedServiceConnectionCard(String botName, String userId, String cardName,String botToken,String roomId) throws IOException, InterruptedException {

        List<Services> services = (List<Services>) pagerDutyAPIService.getServices(botName, userId,false);
        if(services!=null) {
            if (services.size() >= 100) {
                return readCardJson("serviceLookUpCard");
            }
            List<Map<String, String>> servicesList = new ArrayList<>();
            for (Services service : services) {
                Map<String, String> serviceMap = new HashMap<>();
                serviceMap.put("title", service.getServiceName());
                serviceMap.put("value", service.getServiceId());
                servicesList.add(serviceMap);
            }

            ObjectMapper objectMapper = new ObjectMapper();
            InputStream cardTemplateStream = new ClassPathResource("cards/" + cardName + ".json").getInputStream();
            JsonNode cardNode = objectMapper.readTree(cardTemplateStream);

            // Navigate to the "choices" field in the JSON tree
            JsonNode choicesNode = cardNode.at("/body/2/choices");
            if (choicesNode.isArray()) {
                ArrayNode choicesArray = (ArrayNode) choicesNode;
                // Clear any existing choices
                choicesArray.removeAll();

                // Add the new services as choices
                for (Map<String, String> service : servicesList) {
                    ObjectNode newChoice = objectMapper.createObjectNode();
                    newChoice.put("title", service.get("title"));
                    newChoice.put("value", service.get("value"));
                    choicesArray.add(newChoice);
                }
            }

            // Convert the updated JsonNode back to a String
            return objectMapper.writeValueAsString(cardNode);
        }else{
                String linkCardJson = prepareLinkCard(userId,roomId,botName,botToken,"relink");
                webexService.sendDirectMessage(userId,linkCardJson,botToken,true);
        }
        return "";
    }

    public String getCreateCard(String roomId,String userId,String botName,String botToken) throws IOException, InterruptedException {
        String createIncidentCard = readCardJson("createIncidentCard");

        //Get the priorities from PagerDuty API
        List<Map<String, String>> priorityList = pagerDutyAPIService.getPriorities(botName,userId);
        if(priorityList!=null){
        createIncidentCard = updatePriorities(createIncidentCard, priorityList);
        }else{
            String linkCardJson = prepareLinkCard(userId,roomId,botName,botToken,"relink");
            webexService.sendDirectMessage(userId,linkCardJson,botToken,true);
        }
        String botId=botsService.findBotIdByBotName(botName);
        String serviceId = botMembershipService.getCustomDataIdByBotIdAndSpaceId(roomId,botId);

        JsonNode serviceDetail = pagerDutyAPIService.getServiceDetail(botName, userId, serviceId); //Getting service Details from API
        if(serviceDetail!=null){
            if(serviceDetail.has("responseStatus") && serviceDetail.get("responseStatus").asText().equals("unauthorized")){
                String linkCardJson = prepareLinkCard(userId,roomId,botName,botToken,"relink");
                webexService.sendDirectMessage(userId,linkCardJson,botToken,true);
            }
            else {
                String serviceName = serviceDetail.get("service").get("name").asText(); //Extracting the ServiceName from serviceDetail

                String escalationPolicyId = serviceDetail.get("service").get("escalation_policy").get("id").asText();//Extracting the Escalation Policy Id from serviceDetail
                JsonNode escalationPolicyDetail = pagerDutyAPIService.getEscalationPolicy(botName, userId, escalationPolicyId); //Getting EscalationPolicy Details by passing escalationPolicyId, from API
                if (escalationPolicyDetail != null) {
                    if (escalationPolicyDetail.has("responseStatus") && escalationPolicyDetail.get("responseStatus").asText().equals("unauthorized")) {
                        String linkCardJson = prepareLinkCard(userId, roomId, botName, botToken, "relink");
                        webexService.sendDirectMessage(userId, linkCardJson, botToken, true);
                    } else {
                        String escalationPolicyName = escalationPolicyDetail.get("escalation_policy").get("name").asText(); //Extracting escalationPolicyName from escalationPolicyDetail
                        String impactedService = "Impacted Service: " + "**" + serviceName + "**";
                        String escalationPolicy = "Escalation Policy: " + "**" + escalationPolicyName + "**";
                        String createIncidentString = createIncidentCard.replace("\"visibility\"", String.valueOf(false)) //Setting visibility to false
                                .replace("Impacted Service:", impactedService) // Setting impactedService
                                .replace("Escalation Policy:", escalationPolicy) //Setting escalationPolicy
                                .replace("\"deleteFlag\"", String.valueOf(true)); //Setting deleteFlag to true


                        return createIncidentString;
                    }
                }
                else {
                    logger.info("Error while fetching escalation policy details");
                }
            }
        }else{
            logger.info("Error fetching service details");
        }
        return "";
    }

    public JsonNode createIncidentSummaryCard(String botName,String botToken, String spaceId,String roomId, String userId, IncidentCreated incident, String incidentCard, boolean showCreateSpace, boolean showEscalation, boolean priorityVisibility) throws IOException,InterruptedException, ExecutionException {
        ObjectMapper objectMapper = new ObjectMapper();
        // To store the cards to be return(spaceCard and directCard)
        ObjectNode cards = objectMapper.createObjectNode();

        // Load your containersJson and incidentContentCard JSON files using readCardJson method
        Person person = webexService.getPerson(botToken, userId);
        final String[] spaceInfoCardString = new String[1];
        spaceInfoCardString[0]=readCardJson("spaceInfoCard");
        String containersJsonStr = readCardJson("containers");
        JsonNode containersJson = objectMapper.readTree(containersJsonStr);
        String incidentContentCardStr = readCardJson("incidentContent");

        // Create a map from container id to container object
        Map<String, JsonNode> containersMap = new HashMap<>();
        Iterator<JsonNode> containers = containersJson.get("containers").elements();
        while (containers.hasNext()) {
            JsonNode container = containers.next();
            containersMap.put(container.get("id").asText(), container);

            String id = container.get("id").asText();
            if(id.equals("createSpaceContainer")) {
                continue;
            }
            String items = objectMapper.writeValueAsString(container.get("items"));
            incidentContentCardStr = incidentContentCardStr.replace("\"" + id + "\"", items);
        }
        ObjectNode incidentContentCard = (ObjectNode) objectMapper.readTree(incidentContentCardStr);

        // Replace items in incidentContentCard.body
        ArrayNode bodyItems = (ArrayNode) incidentContentCard.get("body");
        for (int i = 0; i < bodyItems.size(); i++) {
            JsonNode item = bodyItems.get(i);
            if ("Container".equals(item.get("type").asText()) && item.get("id") != null && item.get("id").asText().equals("createSpaceContainer")) {
                bodyItems.set(i, containersMap.get(item.get("id").asText()));
            }
        }
        incidentContentCard.set("body", bodyItems);

        // Get the modified content
        JsonNode content = incidentContentCard.get("body");
        JsonNode incidentCardJson = objectMapper.readTree(readCardJson(incidentCard));
        if(incidentCard.equals("warRoomIncidentSummary")){
            String status=pagerDutyAPIService.getStatusByIncidentId(botName,userId,incident.getIncident().getId());
            if(status != null){
                if(status.equals("unauthorized")){
                    String linkCardJson = prepareLinkCard(userId,roomId,botName,botToken,"relink");
                    webexService.sendDirectMessage(userId,linkCardJson,botToken,true);
                }
                else{
                    logger.info("RESPONSEE:"+status);
                    String card=readCardJson("warRoomIncidentSummary");
                    card=card.replace("${status}",status);
                    incidentCardJson=objectMapper.readTree(card);
                }

            }
            else{
                logger.info("Error in fetching Status");
            }
        }
        ObjectNode cardJson = incidentCardJson.deepCopy();
        ArrayNode contentBody = (ArrayNode) cardJson.get("body");

        // Modify cardJson.attachments[0].content.body
        ArrayNode newContentBody = objectMapper.createArrayNode();
        for (JsonNode item : contentBody) {
            if (item.isTextual() && "incidentJson".equals(item.asText())) {
                for (JsonNode contentItem : content) {
                    newContentBody.add(contentItem);
                }
            } else {
                newContentBody.add(item);
            }
        }
        cardJson.set("body", newContentBody);

        // Convert the modified cardJson back to string
        String modifiedIncidentCard = objectMapper.writeValueAsString(cardJson);

        JsonNode rootNode = pagerDutyAPIService.getAllUsers(botName, userId);
        if(rootNode!=null) {
            if (rootNode.has("responseStatus") && rootNode.get("responseStatus").asText().equals("unauthorized")) {
                String linkCardJson = prepareLinkCard(userId, roomId, botName, botToken, "relink");
                webexService.sendDirectMessage(userId, linkCardJson, botToken, true);
            } else {
        JsonNode usersNode = rootNode.path("users");

                List<PagerUser> users = Arrays.asList(objectMapper.treeToValue(usersNode, PagerUser[].class));

                final String[] userUrlHolder = new String[1];
                for (PagerUser user : users) {
                    if (user.getEmail().contains(person.getEmails().get(0)))
                        userUrlHolder[0] = user.getHtmlUrl();
                }

                //Getting oncall user details for escalation drop down
                List<String> escalationPolicyList = List.of(incident.getIncident().getEscalationPolicy().getId());
                rootNode = pagerDutyAPIService.getOnCallUsers(botName, userId, incident.getIncident().getService().getId(), escalationPolicyList);
                if (rootNode != null) {
                    if (rootNode.has("responseStatus") && rootNode.get("responseStatus").asText().equals("unauthorized")) {
                        String linkCardJson = prepareLinkCard(userId, roomId, botName, botToken, "relink");
                        webexService.sendDirectMessage(userId, linkCardJson, botToken, true);
                    } else {
                        JsonNode oncallsUsers = rootNode.get("oncalls");
                        Oncall[] oncallResponse = objectMapper.readValue(oncallsUsers.toString(), Oncall[].class);

                        // Process the oncallresponse data
                        Map<Integer, String> oncallUsersMap = new HashMap<>();
                        for (Oncall oncall : oncallResponse) {
                            int escalationLevel = oncall.getEscalationLevel() + 1;
                            String value;
                            if (oncall.getSchedule() != null) {
                                value = oncall.getSchedule().getSummary();
                            } else {
                                value = oncall.getUser().getSummary();
                            }
                            oncallUsersMap.put(escalationLevel, value);
                        }

                        // Print the result
                        oncallUsersMap.forEach((level, summary) ->
                                logger.info("Escalation Level: " + level + ", Summary: " + summary)
                        );


                        List<Map<String, String>> escalationList = new ArrayList<>();
                        for (Map.Entry<Integer, String> entry : oncallUsersMap.entrySet()) {
                            int escalationLevel = entry.getKey();
                            String escalation_users = entry.getValue() ;
                            String title = String.format("%s (level %d)", escalation_users, escalationLevel);

                            Map<String, String> dropdownEntry = new HashMap<>();
                            dropdownEntry.put("title", title);
                            dropdownEntry.put("value", String.valueOf(escalationLevel));

                            escalationList.add(dropdownEntry);
                        }

                        //Getting assignee details
                        List<IncidentCreated.Incident.Assignment> assignees = incident.getIncident().getAssignments();
                        List<String> assigneeIds = assignees.stream()
                                .map(assignment -> assignment.getAssignee().getId())
                                .collect(Collectors.toList());

                        List<CompletableFuture<String>> futures = getUsers(botName, userId, assigneeIds);

                        // Combine all futures to wait for completion
                        CompletableFuture<Void> allOf = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
                        Map<String, String> userUrlMap = new HashMap<>();
                        final String[] cardJsonHolder = {modifiedIncidentCard};
                        // After all futures are done, print the results
                        allOf.thenApply(v -> {
                            try {
                                futures.forEach(future -> {
                                    try {
                                        String userData = future.get();
                                        PagerUser user = parseUser(userData);// Parse the user data from the JSON string
                                        userUrlMap.put(user.getName(), user.getHtmlUrl());

                                    } catch (Exception e) {
                                        logger.error("Error in createIncidentSummaryCard: ", e);
                                    }
                                });
                                List<String> assigneeNames = assignees.stream()
                                        .map(assignment -> assignment.getAssignee().getSummary())
                                        .collect(Collectors.toList());

                                // Create assignedTo string
                                String assignedTo = assigneeNames.stream()
                                        .map(name -> String.format("[%s](%s)", name, userUrlMap.get(name)))
                                        .collect(Collectors.joining(", "));

                                // Create a map of placeholders and their values
                                Map<String, Object> placeholders = new HashMap<>();
                                placeholders.put("number", incident.getIncident().getIncidentNumber());
                                placeholders.put("incidentId", incident.getIncident().getId());
                                placeholders.put("incidentTitle", incident.getIncident().getTitle());
                                placeholders.put("incidentUrl", incident.getIncident().getHtmlUrl());
                                placeholders.put("priorityVisibility", priorityVisibility);
                                placeholders.put("serviceName", incident.getIncident().getService().getSummary());
                                placeholders.put("serviceUrl", incident.getIncident().getService().getHtmlUrl());
                                placeholders.put("triggeredBy", person.getDisplayName());
                                placeholders.put("triggeredByUrl", userUrlHolder[0]);
                                placeholders.put("assignedTo", assignedTo);
                                placeholders.put("urgency", incident.getIncident().getUrgency());
                                placeholders.put("escalationList", new JSONArray(escalationList));
                                placeholders.put("escalationPolicyId", incident.getIncident().getEscalationPolicy().getId());
                                placeholders.put("incident", incident.toString());
                                if (incident.getIncident().getBody() != null && incident.getIncident().getBody().getDetails() != null ) {
                                    placeholders.put("description", incident.getIncident().getBody().getDetails());
                                } else {
                                    placeholders.put("description", "");
                                }
                                if (priorityVisibility)
                                    placeholders.put("priority", incident.getIncident().getPriority().getSummary());

                                boolean showDescription = false;
                                String description = "";

                                for (Map.Entry<String, Object> entry : placeholders.entrySet()) {
                                    String key = entry.getKey();
                                    Object value = entry.getValue();

                                    if (value instanceof JSONArray) {
                                        // Handle array replacement
                                        String placeholderPattern = "\"\\$\\{" + key + "\\}\"";
                                        cardJsonHolder[0] = cardJsonHolder[0].replaceAll(placeholderPattern, Matcher.quoteReplacement(value.toString()));
                                    } else if (value instanceof Boolean) {
                                        // Handle boolean replacement
                                        String placeholderPattern = "\"\\$\\{" + key + "\\}\"";
                                        cardJsonHolder[0] = cardJsonHolder[0].replaceAll(placeholderPattern, value.toString());
                                    } else {
                                        // Handle other types of replacement
                                        String placeholderPattern = "\\$\\{" + key + "\\}";
                                        cardJsonHolder[0] = cardJsonHolder[0].replaceAll(placeholderPattern, Matcher.quoteReplacement(value.toString()));
                                        spaceInfoCardString[0] = spaceInfoCardString[0].replaceAll(placeholderPattern, Matcher.quoteReplacement(value.toString()));
                                    }

                                    if (key.equals("description") && value != null && !value.equals("")) {
                                        showDescription = true;
                                        description = value.toString();
                                    }
                                }

                                JsonNode updatedIncidentCard = objectMapper.readTree(cardJsonHolder[0]);

                                if (showDescription) {
                                    int ind = showCreateSpace ? 1 : 2;


                                    ArrayNode bodyArray = (ArrayNode) updatedIncidentCard.path("body");
                                    while (bodyArray.size() <= ind) {
                                        bodyArray.add(objectMapper.createArrayNode());
                                    }

                                    //ArrayNode itemsArray = (ArrayNode) bodyArray.get(ind);
                                    JsonNode element = bodyArray.get(ind);
                                    ArrayNode itemsArray = (ArrayNode) element.get("items");
                                    if ("ColumnSet".equals(itemsArray.get(0).get("type").asText())) {
                                        ArrayNode columnsArray = (ArrayNode) itemsArray.get(0).path("columns");

                                        // Ensure the columns array has at least two columns
                                        while (columnsArray.size() <= 1) {
                                            columnsArray.add(objectMapper.createObjectNode().putArray("items"));
                                        }

                                        // Access the items array within each column
                                        ArrayNode column0Items = (ArrayNode) columnsArray.get(0).path("items");
                                        ArrayNode column1Items = (ArrayNode) columnsArray.get(1).path("items");

                                        // Example: Add a new TextBlock to each column
                                        column0Items.add(objectMapper.createObjectNode()
                                                .put("type", "TextBlock")
                                                .put("text", "Description")
                                                .put("wrap", true)
                                                .put("horizontalAlignment", "right"));

                                        column1Items.add(objectMapper.createObjectNode()
                                                .put("type", "TextBlock")
                                                .put("text", description)
                                                .put("wrap", true)
                                                .put("horizontalAlignment", "left"));
                                    }
                                }

                                // Get the body array
                                ArrayNode bodyArray = (ArrayNode) updatedIncidentCard.path("body");

                                // Iterate over the body array and update visibility
                                for (JsonNode itemNode : bodyArray) {
                                    if (itemNode.get("type").asText().equals("Container") && (itemNode.get("id") != null)) {
                                        String id = itemNode.get("id").asText();
                                        ObjectNode objectNode = (ObjectNode) itemNode;

                                        if ("createSpaceContainer".equals(id)) {
                                            objectNode.put("isVisible", showCreateSpace);
                                        } else if ("escalateButtonContainer".equals(id)) {
                                            objectNode.put("isVisible", showEscalation);
                                        }
                                    }
                                }
                                String resultJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(updatedIncidentCard);

                                if (spaceId == null || spaceId.equals("")) {
                                    logger.info("RESULT JSON IS " + resultJson);
                                    // Adding a card for sending to 1-to-1 space
                                    cards.put("directCard", objectMapper.readTree(resultJson));
                                } else {
                                    if (!showCreateSpace) {
                                        logger.info("body Array " + bodyArray);
                                        ArrayNode actionsArray = (ArrayNode) bodyArray.get(3).path("actions");
                                        ObjectNode newAction = objectMapper.createObjectNode();
                                        newAction.put("type", "Action.Submit");
                                        newAction.put("title", "On Call Now");
                                        ObjectNode dataNode = objectMapper.createObjectNode();
                                        dataNode.put("requestType", "oncall");
                                        // Mocking incident object for demonstration
                                        ObjectNode incidentNode = objectMapper.createObjectNode().put("id", "12345");
                                        dataNode.put("incident", incidentNode.toString());
                                        newAction.set("data", dataNode);

                                        // Insert the new action at index 2
                                        if (actionsArray.size() < 2) {
                                            for (int i = actionsArray.size(); i < 2; i++) {
                                                actionsArray.add(objectMapper.createObjectNode());
                                            }
                                        }
                                        actionsArray.insert(2, newAction);
                                        String result = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(updatedIncidentCard);
                                        logger.info("CARD JSON IS " + resultJson);
                                        // Adding a card for sending to newly created space
                                        cards.put("spaceCard", objectMapper.readTree(result));

                                        logger.info("room Id " + roomId);
                                        logger.info("space Id " + spaceId);
                                        String decodedSpaceId = getDecodedSpaceId(spaceId);
                                        logger.info("decoded " + decodedSpaceId);
                                        // Create the replacement string
                                        String replacementString = "webexteams://im?space=" + decodedSpaceId;
                                        String webexUrl = "webexUrl";
                                        // Perform the replacement using regex
                                        String regex = "\\$\\{" + Pattern.quote(webexUrl) + "\\}";
                                        spaceInfoCardString[0] = spaceInfoCardString[0].replaceAll(regex, Matcher.quoteReplacement(replacementString));

                                        logger.info("UPDATED SPACE INFO CARD " + spaceInfoCardString[0]);
                                        // Adding a card for sending to 1-to-1 space
                                        cards.put("directCard", objectMapper.readTree(spaceInfoCardString[0]));
                                    } else {
                                        logger.info("In else create summary for showCreateSpace");
                                        webexService.sendMessageToRoom(botToken, roomId, resultJson, true);
                                    }
                                }
                            } catch (Exception e) {
                                logger.error("Error in createIncidentSummaryCard: ", e);
                            }

                            return cardJsonHolder[0];
                        }).get();
                        return cards;
                    }
                } else {
                    logger.info("error fetching oncall user");
                }
            }
        }else{
            logger.info("Error fetching userUrl");
        }
        return null;
    }

     /**
     *
     * @param path
     * @param servicesList
     * @param key
     * @return
     * @throws IOException
     */
    public String getUpdatedListServiceCard(String path, List<Map<String, String>> servicesList, String key) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        InputStream cardTemplateStream = new ClassPathResource(path).getInputStream();
        JsonNode cardNode = objectMapper.readTree(cardTemplateStream);

        // Set the results text based on the key
        JsonNode resultsTextNode = cardNode.at("/body/0/text");
        if (resultsTextNode.isTextual()) {
            // Create a new TextNode with the updated text
            ((ObjectNode) cardNode.get("body").get(0)).put("text", "Results for " + key);
        }

        // Navigate to the "choices" field in the JSON tree
        JsonNode choicesNode = cardNode.at("/body/2/choices");
        if (choicesNode.isArray()) {
            ArrayNode choicesArray = (ArrayNode) choicesNode;
            // Clear any existing choices
            choicesArray.removeAll();

            // Add the new services as choices
            for (Map<String, String> service : servicesList) {
                ObjectNode newChoice = objectMapper.createObjectNode();
                newChoice.put("title", service.get("title")); // Service name
                newChoice.put("value", service.get("value")); // Service ID
                choicesArray.add(newChoice);
            }
        }

        // Convert the updated JsonNode back to a String
        return objectMapper.writeValueAsString(cardNode);
    }

    public List<CompletableFuture<String>> getUsers(String botName,String userId, List<String> userIds) {
        return userIds.stream()
                .map(pagerUser -> pagerDutyAPIService.getUserDetails(botName,userId, pagerUser))
                .collect(Collectors.toList());
    }

    public PagerUser parseUser(String userData) throws JsonProcessingException{
        // Your implementation here to parse user data

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode rootNode = objectMapper.readTree(userData);
        JsonNode userNode = rootNode.path("user");
        PagerUser user = objectMapper.treeToValue(userNode,PagerUser.class);
        return user;
    }

    public static String getDecodedSpaceId(String spaceId) {
        // Decode the Base64-encoded space ID
        byte[] decodedBytes = Base64.getDecoder().decode(spaceId);
        String decoded = new String(decodedBytes);

        // Split the decoded string by "/"
        String[] parts = decoded.split("/");

        // Get the last part
        String decodedSpaceId = parts[parts.length - 1];

        return decodedSpaceId;
    }

    public String updatePriorities(String card, List<Map<String, String>> priorityList) throws JSONException {
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            // Convert the priority list to a JSON string
            String prioritiesJson = objectMapper.writeValueAsString(priorityList);

            // Parse the original card JSON string into a JSONObject
            JSONObject jsonObject = new JSONObject(card);

            // Navigate to the "body" array
            JSONArray bodyArray = jsonObject.getJSONArray("body");

            for (int i = 0; i < bodyArray.length(); i++) {
                JSONObject bodyItem = bodyArray.getJSONObject(i);

                // Check if the item is the "incident_priority" ChoiceSet
                if (bodyItem.has("id") && bodyItem.getString("id").equals("incident_priority")) {
                    // Create a new ChoiceSet with the updated priorities
                    JSONObject newChoiceSet = new JSONObject();
                    newChoiceSet.put("type", "Input.ChoiceSet");
                    newChoiceSet.put("id", "incident_priority");
                    newChoiceSet.put("choices", new JSONArray(prioritiesJson));
                    newChoiceSet.put("placeholder", "Select");

                    // Replace the old ChoiceSet with the new one
                    bodyArray.put(i, newChoiceSet);
                    break;
                }
            }
            // Convert the updated JSONObject back to a string
            return jsonObject.toString(2); // Indent with 2 spaces for readability
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public String getPrepareOnCallCard(String serviceName, String escalationLevel, List<String> users) throws JsonProcessingException {
        ObjectMapper objectMapper = new ObjectMapper();

        // Reading onCallCard Json as a String
        String onCallCardString = readCardJson("onCallCard");

        // Replace placeholders for service name and escalation level
        onCallCardString = onCallCardString.replace("{{serviceName}}", serviceName)
                .replace("{{escalationLevel}}", escalationLevel);

        // Parse the JSON string into an ObjectNode
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode cardNode = (ObjectNode) mapper.readTree(onCallCardString);
        ArrayNode bodyNode = (ArrayNode) cardNode.get("body");

        // Add user information blocks
        for (String user : users) {
            JsonNode userJson = objectMapper.readTree(user);
            ObjectNode userTextBlock = mapper.createObjectNode();
            userTextBlock.put("type", "TextBlock");
            userTextBlock.put("text", userJson.get("user").get("name").asText() + " (" + userJson.get("user").get("email").asText() + ")");
            userTextBlock.put("wrap", true);
            bodyNode.add(userTextBlock);
        }

        // Convert the updated cardNode back to a JSON string
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(cardNode);

    }
    /**
     * Removes a button from the given card.
     *
     * @param content content of Card in which we want to remove button
     * @param buttonNames List of Button Names which we want to remove.
     *
     * @return a String indicating the card without the specified list buttons.
     * @throws IOException If an I/O error occurs during the operation.
     * @throws InterruptedException If the operation is interrupted.
     */
    public String removeButtonFromCard(JsonNode content, List<String> buttonNames) throws IOException, InterruptedException {
        ObjectMapper objectMapper = new ObjectMapper();

        String contentString = objectMapper.writeValueAsString(content); // Converting Content from JsonNode to String

        String updatedContentString;
        updatedContentString = updateButtonActions(contentString, buttonNames); //Updating Attachments
        updatedContentString = updateVisibility(updatedContentString, buttonNames); //Updating Visibility of the Buttons

        return updatedContentString;
    }

    /**
     * Updates ButtonActions in the card by removing the given Button.
     *
     * @param contentString ContentString in which we want to update ButtonActions.
     * @param buttonNames List of the button names which we want to remove.
     *
     * @return A String indicating the updatedAttachments
     * @throws JsonProcessingException If an error occurs during JSON processing.
     */
    private String updateButtonActions(String contentString, List<String> buttonNames) throws JsonProcessingException {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode content = objectMapper.readTree(contentString); //Converting contentString to JsonNode

        JsonNode buttonActions = objectMapper.createObjectNode(); //Extracting buttonActions from the contentString
        ArrayNode updatedButtonActions = objectMapper.createArrayNode(); //Creating Empty arrayNode to store the updated Button Actions

        for(JsonNode item: content.get("body")) {

            // Traverse through all the items in the body and get the item which type is ActionSet
            if(item.get("type").asText().equals("ActionSet")) {
                buttonActions = item.get("actions");

                // Traverse through all the button Actions and ignore the button which has the title as given buttonName
                for(JsonNode button: buttonActions) {
                    if(!buttonNames.contains(button.get("title").asText())) { // Checking the button title is equal to given buttonName or not
                        updatedButtonActions.add(button); // adding the buttons which is not equal to the given button, to the updatedButtonActions
                    }
                }
                break;
            }
        }

        // return contentString if updatedButtons is empty (that means there is no button with given buttonName and and there is no change in Buttons)
        // else return the AttachmentString after updating
        String buttonActionString = objectMapper.writeValueAsString(buttonActions); //Converting buttonActions to String
        String updatedButtonActionString = objectMapper.writeValueAsString(updatedButtonActions); //Converting updatedButtonActions to String

        String updatedContentString = contentString;
        if(!updatedButtonActionString.isEmpty()) {
            // Update the contentString by replacing the buttonActions with the updatedButtonActions
            updatedContentString = contentString.replace(buttonActionString, updatedButtonActionString);
        }
        return updatedContentString;
    }

    /**
     * Sets the contentVisibility to false in attachments for the Buttons which have visible option.
     *
     * @param contentString ContentString in which we want to update visibility.
     * @param buttonNames List of Button Names which we want to remove.
     *
     * @return A String indicating updatedAttachment
     * @throws JsonProcessingException If an error occurs during JSON processing.
     */
    private String updateVisibility(String contentString, List<String> buttonNames) throws JsonProcessingException {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode content = objectMapper.readTree(contentString); //Converting ContentString into JsonNode
        if(buttonNames.contains("Escalate")) {
            JsonNode body = content.get("body");
            String bodyString = objectMapper.writeValueAsString(body);
            ArrayNode updatedBody = objectMapper.createArrayNode();
            for(JsonNode item: body) {
                ObjectNode objectNode = (ObjectNode) item;
                if(item.has("type") && item.has("id")) {
                    if(item.get("type").asText().equals("Container") && item.get("id").asText().equals("escalateButtonContainer")) {
                        objectNode.put("isVisible", false);
                    } else if(item.get("type").asText().equals("Container") && item.get("id").asText().equals("createSpaceContainer")) {
                        objectNode.put("isVisible", false);
                    }
                }
                updatedBody.add(item);
            }

            String updatedBodyString = objectMapper.writeValueAsString(updatedBody);
            contentString = contentString.replace(bodyString, updatedBodyString);

        }
        return contentString;
    }
}
