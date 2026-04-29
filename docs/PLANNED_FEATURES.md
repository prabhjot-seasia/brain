# Planned Features

> **Who this document is for:** Engineers and product stakeholders who want to know what Brain will do next.

---

## Under Consideration

The following capabilities are being evaluated for future development. This list is not a commitment — it reflects areas of active exploration.

### Enhanced Cross-Repository Dependency Analysis
Deeper static analysis of method-level call graphs across repositories, including interface implementation tracking and override chain resolution.

### ONNX-Based Prompt Injection Classification
Replace the current regex-based prompt injection detector with a lightweight ONNX model for higher accuracy on adversarial inputs.

### Gradle and package.json Dependency Parsing (Deep)
Full dependency-tree resolution for Gradle build files (including composite builds) and npm workspaces, beyond the current top-level parsing.

### Interactive Plan Editing
Allow users to modify individual steps in a generated plan before execution — adding, removing, or reordering edit obligations through the UI.

### Characterisation Test Generation
Automatically generate tests for code that Brain is about to modify, based on the Seam Analyzer's NEEDS_CHARACTERISATION_TEST flag.

### Multi-Language Support
Extend ingestion and code generation beyond Java and JavaScript to Python, Go, and Rust.

---

*Feature requests and suggestions are welcome via GitHub Issues.*
