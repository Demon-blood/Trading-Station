M12 v1.8 — PartialFillMath BigDecimal test fix

Test-only change. No Android/runtime/trading logic changes.

Root cause:
PartialFillMath.incrementalQuantity(BigDecimal("1.00"), BigDecimal("1.00"))
returns a numerically-zero BigDecimal with scale 2 (0.00).

JUnit's assertEquals(BigDecimal.ZERO, value) uses BigDecimal.equals(), which is
scale-sensitive, so 0.00 != 0 by equals() even though both are numerically zero.

The corrected regression test uses compareTo(BigDecimal.ZERO) == 0.

Replace exactly:
tools/m12_payload/app/src/test/java/com/ksp/cryptobot/lifecycle/PartialFillMathTest.kt

Commit to main, then launch a NEW M12 workflow from main.
