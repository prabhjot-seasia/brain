package com.assurant.brain.graph.node;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@ToString
@Node("EventSchema")
public class EventSchemaNode {

    @Id
    private String id;

    @Property("projectId")
    private String projectId;

    @Property("channelName")
    private String channelName;

    @Property("messageName")
    private String messageName;

    @Property("operation")
    private String operation;

    @Property("contentType")
    private String contentType;

    @Property("source")
    private String source;

    @Property("payloadFields")
    private List<String> payloadFields = new ArrayList<>();
}
