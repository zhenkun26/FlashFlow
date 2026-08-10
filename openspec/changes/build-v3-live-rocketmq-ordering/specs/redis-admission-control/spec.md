## ADDED Requirements

### Requirement: Live transport outcomes preserve admission accounting
The system SHALL bind real RocketMQ publication and consumption outcomes to the existing stable admission identity and SHALL release, retain, confirm, or quarantine capacity only from attributable evidence that makes the transition safe.

#### Scenario: Broker definitely rejects before acceptance
- **WHEN** the live producer proves that no command can be consumed and no MySQL order attempt can result
- **THEN** the held admission is safely released exactly once

#### Scenario: Broker acknowledgement is observed
- **WHEN** the live producer receives trustworthy acceptance for the command
- **THEN** the admission remains unavailable while the accepted command is pending consumption

#### Scenario: Consumer commits an effective order
- **WHEN** live command consumption commits an effective MySQL order
- **THEN** the admission is confirmed from durable outcome evidence and repeated delivery does not consume or confirm another token

#### Scenario: Publication or consumption remains ambiguous
- **WHEN** transport evidence cannot prove whether a command was accepted or a business transaction committed
- **THEN** capacity remains retained or quarantined until replay or reconciliation resolves it from the stable identity

#### Scenario: Dead-lettered command has no proven business result
- **WHEN** a command reaches the dead-letter path without committed MySQL evidence
- **THEN** the system does not blindly release admission capacity and exposes the command for bounded reconciliation

