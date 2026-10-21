package io.github.laalka.slav.core

import java.math.BigDecimal

// Unit conversions shift the decimal point and are exact. BigDecimal `/` keeps the scale of the
// dividend (BigDecimal("1000000") / BigDecimal("1E9") is 0), so it is never used for units.

val PI: BigDecimal = BigDecimal("3.14159265358979323846")

/** mm → cm */
fun BigDecimal.mmToCm(): BigDecimal = movePointLeft(1)

/** mm → m */
fun BigDecimal.mmToM(): BigDecimal = movePointLeft(3)

/** mm² → m² */
fun BigDecimal.mm2ToM2(): BigDecimal = movePointLeft(6)

/** mm³ → m³ */
fun BigDecimal.mm3ToM3(): BigDecimal = movePointLeft(9)
