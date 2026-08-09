## ADDED Requirements

### Requirement: Admission survives future publication uncertainty safely
The system SHALL classify future command publication as definitely not published, broker acknowledged, or ambiguous, and SHALL change a held admission only when that classification makes the transition safe.

#### Scenario: Publication definitely did not occur
- **WHEN** the producer proves that no command reached the broker and no MySQL attempt can result
- **THEN** it may safely release the held admission exactly once

#### Scenario: Broker acknowledges publication
- **WHEN** the broker acknowledges the command but MySQL has not completed it
- **THEN** the admission remains unavailable until command consumption confirms, safely releases, or quarantines it from durable outcome evidence

#### Scenario: Publication outcome is ambiguous
- **WHEN** the producer cannot prove whether the broker accepted the command
- **THEN** it retains or quarantines the admission and retry reuses the same command identity rather than manufacturing capacity
