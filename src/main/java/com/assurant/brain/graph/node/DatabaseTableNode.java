package com.assurant.brain.graph.node;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@ToString
@Node("DatabaseTable")
public class DatabaseTableNode {

    @Id
    private String id;

    @Property("projectId")
    private String projectId;

    @Property("schemaName")
    private String schemaName;

    @Property("tableName")
    private String tableName;

    @Property("purpose")
    private String purpose;

    @Property("source")
    private String source;

    @Relationship(type = "HAS_COLUMN", direction = Relationship.Direction.OUTGOING)
    private List<DatabaseColumnNode> columns = new ArrayList<>();
}
