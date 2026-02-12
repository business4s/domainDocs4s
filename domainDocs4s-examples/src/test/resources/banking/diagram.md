
```mermaid
erDiagram
LiabilityType
Application {
 applicant Party
 submittedAt LocalDateTime
 id Id
 income Option[Income]
 status ApplicationStatus
 liabilities List[Liability]
 terms LoanTerms
}
Application ||--|| ApplicationId : has
Application ||--o{ Liability : has
Application ||--|| ApplicationStatus : has
Application ||--|| LoanTerms : has
Application ||--o| Income : has
IncomeSource
Liability {
 liabilityType LiabilityType
 durationInMonths Int
 amount Money
}
Liability ||--|| LiabilityType : has
Income {
 source IncomeSource
 monthlyAmount Money
}
Income ||--|| IncomeSource : has
ApplicationStatus
ApplicationId {
 value UUID
}
LoanTerms {
 purpose String
 durationInMonths Int
 amount Money
}
```
