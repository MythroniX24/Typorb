package com.typorb.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Slightly tightened, wide-tracking display type for a technical feel. */
private val Display = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Bold,
    fontSize = 34.sp,
    lineHeight = 40.sp,
    letterSpacing = (-0.6).sp,
)

private val Title = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.SemiBold,
    fontSize = 20.sp,
    lineHeight = 26.sp,
    letterSpacing = (-0.2).sp,
)

private val Body = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Normal,
    fontSize = 15.sp,
    lineHeight = 22.sp,
    letterSpacing = 0.1.sp,
)

private val Label = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Medium,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.6.sp,
)

val TyporbTypography = Typography(
    headlineLarge = Display,
    headlineSmall = Title,
    titleMedium = Title,
    bodyLarge = Body,
    bodyMedium = Body,
    bodySmall = Body.copy(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = Label,
    labelMedium = Label,
    labelSmall = Label.copy(fontSize = 11.sp),
)