package com.cherret.zaprett.data

data class StrategyCheckResult (
    val path : String,
    val name : String,
    val progress : Float,
    var domains: List<String>,
    val status : StrategyTestingStatus,
)
