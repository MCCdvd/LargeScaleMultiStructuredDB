# UML diagram

```mermaid
classDiagram
    class Standard_Product {
        name
        description
    }
    class Actual_Product {

    }
    class Order {
        orderNumber
        quantity
        expectedPallets
        okProductsPallets
        statusActual_Product
        minimumPallets
    }
    class Pallet {
        palletId
        sequentialNumber
        preparationTimestamp
        canCount
        status
    }
    class Sample {
        sampleId
        methodType
        collectionDate
        origin
    }
    class Measurement_Executed {
        characteristic
        value
        UOM
        origin
        date
    }
    class Quality_Measurement {
        machine
        UOM
        lowerAcceptable
        upperAcceptable
        
    }
    class Machine {
        component
        
    }
    class Batch {
         number
    }
    Standard_Product "1" -- "0..*" Actual_Product : Instance
    Actual_Product "1" -- "0..*" Machine : requires
    Machine "1" -- "0..*" Quality_Measurement : computes
    Quality_Measurement "1" -- "0..*" Measurement_Executed : check
    Actual_Product "1" -- "0..*" Pallet : composed
    Actual_Product "1" -- "0 .. *" Batch : Part of
    Batch "1 .. 2" -- "1" Pallet
    Actual_Product "1" -- "0..*" Order : concerns
    Order "1" -- "0..*" Pallet : comprises
    Pallet "1" -- "0..*" Sample : checkedVia
    Sample "1" -- "1..*" Measurement_Executed : contains
    Pallet "1" -- "0..*" QualityEvaluation : receives
    QualityEvaluation "0..*" -- "1..*" Sample : uses
    ```