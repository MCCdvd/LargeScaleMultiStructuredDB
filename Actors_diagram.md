# Actors Diagram

```mermaid
flowchart LR
    Customer --> a["Create order"]
    Production_Machine --> b["Create product"]
    Quanilty_Control_Machine --> c["Create Measurement"]
    Manager --> d["Queries"]
```