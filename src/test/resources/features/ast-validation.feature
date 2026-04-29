@embedded
Feature: AST Validation of Generated Code
  As a code generation platform
  I want generated code to pass AST validation before LLM review
  So that structural issues are caught without burning LLM tokens

  Background:
    Given the Brain API is running with mocked LLM services

  Scenario: Valid Java code passes AST validation
    Given generated code for "src/main/java/com/example/Service.java" is:
      """
      package com.example;

      public class Service {
          public void execute() {}
      }
      """
    When AST validation is performed on the generated code
    Then the AST validation passes with no issues

  Scenario: Invalid Java code fails AST validation
    Given generated code for "src/main/java/com/example/Bad.java" is:
      """
      package com.example;

      public class Bad {
          public void missing_brace() {
      }
      """
    When AST validation is performed on the generated code
    Then the AST validation fails with syntax errors

  Scenario: Convention violations detected at AST level
    Given generated code for "src/main/java/com/example/BadService.java" is:
      """
      package com.example;

      import org.springframework.beans.factory.annotation.Autowired;

      public class BadService {
          @Autowired
          private String dependency;
      }
      """
    When convention checking is performed on the generated code
    Then convention violations include "field-injection"
