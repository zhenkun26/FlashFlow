## ADDED Requirements

### Requirement: Live application messaging has revision-bound fault evidence
The verification suite SHALL exercise the actual V3 HTTP producer, RocketMQ consumers, command ledger, Redis admission lifecycle, ordering transaction, delayed trigger, and scanner against pinned resolved inputs for one attributable revision.

#### Scenario: Live end-to-end gate passes
- **WHEN** accepted commands and delayed triggers traverse the real application and pinned broker topology and every required invariant reconciles
- **THEN** the report records the revision, resolved broker and client inputs, application configuration, separate transport and business counts, and `PASS`

#### Scenario: Required component is simulated or stale
- **WHEN** a required V3 gate uses only the transport-neutral harness, belongs to another revision, or omits a live boundary
- **THEN** V3 implementation evidence remains `NOT_RUN`, `FAIL`, or `BLOCKED` rather than `PASS`

### Requirement: Live failure drills cover transport and process boundaries
The verification suite SHALL exercise broker unavailability, definitive and ambiguous producer outcomes, duplicate delivery, process interruption before and after MySQL commit, acknowledgement loss, retry exhaustion, dead-letter handling, delayed-message loss, and scanner recovery.

#### Scenario: Producer acknowledgement is lost
- **WHEN** a drill allows possible broker acceptance but hides the producer acknowledgement
- **THEN** the endpoint does not claim acceptance, admission is not unsafely returned, and retry under the stable command identity creates at most one business effect

#### Scenario: Consumer restarts after commit
- **WHEN** a drill stops the consumer after MySQL completion but before delivery acknowledgement
- **THEN** redelivery recovers one durable result and the report reconciles the duplicate attempt without another business effect

#### Scenario: Message reaches the dead-letter path
- **WHEN** a declared poison or retry-exhaustion fixture is processed
- **THEN** its dead-letter evidence is inspectable, no unsupported business success is reported, and any retained admission is reconciled explicitly

#### Scenario: Delayed trigger is unavailable
- **WHEN** delayed publication or delivery is prevented for an expiring order
- **THEN** the scanner closes the eligible order within its declared local recovery bound and the report distinguishes trigger loss from committed closure

