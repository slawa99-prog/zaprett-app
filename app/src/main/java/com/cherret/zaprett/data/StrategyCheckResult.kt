package com.cherret.zaprett.data

data class StrategyCheckResult (
    val path : String,
    val name : String,
    val progress : Float,
    val domains: List<String>,
    val status : StrategyTestingStatus,
    val problem: String = "",
    val checkedDomains: Int = 0,
)
