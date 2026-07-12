package com.wici.androidalbumdemo

data class SplatStreamAccounting(
    val wireRecords: Int = 0,
    val renderEligibleRecords: Int = 0,
    val alphaFilteredRecords: Int = 0,
    val invalidRecords: Int = 0,
    val partialTrailingBytes: Int = 0
) {
    val countConserved: Boolean
        get() = wireRecords == renderEligibleRecords + alphaFilteredRecords + invalidRecords

    operator fun plus(other: SplatStreamAccounting) = SplatStreamAccounting(
        wireRecords + other.wireRecords,
        renderEligibleRecords + other.renderEligibleRecords,
        alphaFilteredRecords + other.alphaFilteredRecords,
        invalidRecords + other.invalidRecords,
        partialTrailingBytes + other.partialTrailingBytes
    )
}
