package com.assurant.brain.graph.node;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;

@Getter
@Setter
@ToString
@Node("DatabaseColumn")
public class DatabaseColumnNode {

    @Id
    private String id;

    @Property("tableId")
    private String tableId;

    @Property("columnName")
    private String columnName;

    @Property("dataType")
    private String dataType;

    @Property("defaultValue")
    private String defaultValue;

    @Property("nullable")
    private boolean nullable;

    @Property("comment")
    private String comment;
}
