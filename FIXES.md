# Bug Fixes - Country-Based Product API

## Summary

Three critical bugs were identified and fixed:
1. Incorrect discount calculation (additive vs multiplicative)
2. Race condition in concurrent discount application
3. Missing JSON serialization configuration

All 12 tests now pass.

---

## Bug 1: Additive vs Multiplicative Discount Calculation

**Location:** `src/main/kotlin/io/nexure/discount/service/ProductService.kt`

The service was summing discount percentages instead of applying them multiplicatively. This violates the business requirement where discounts compound.

**Original Implementation:**
```kotlin
val totalDiscountPercent = discounts.sumOf { it.percent / 100.0 }
return basePrice * (1 - totalDiscountPercent) * (1 + vatRate)
```

For a product with basePrice=1000, two discounts (10%, 20%), and 25% VAT:
- Wrong calculation: `1000 × (1 - 0.30) × 1.25 = 875`

**Corrected Implementation:**
```kotlin
val discountMultiplier = discounts.fold(1.0) { acc, discount ->
    acc * (1 - discount.percent / 100.0)
}
return basePrice * discountMultiplier * (1 + vatRate)
```

Now correctly: `1000 × (1 - 0.10) × (1 - 0.20) × 1.25 = 900`

Each discount applies to the already-reduced price, which is the standard business model.

**Test Coverage:**
- should calculate final price correctly with single discount
- should calculate final price correctly with multiple discounts

---

## Bug 2: Race Condition in Concurrent Discount Application

**Location:** `src/main/kotlin/io/nexure/discount/repository/ProductRepository.kt`

The applyDiscount method used a non-atomic read-modify-write pattern. When 25 concurrent requests applied the same discount, all would pass the idempotency check and duplicate discounts would appear in the database.

**Original Implementation:**
```kotlin
suspend fun applyDiscount(productId: String, discount: Discount): Product? {
    val product = findById(productId) ?: return null  // Read
    
    val hasDiscount = product.discounts.any { it.discountId == discount.discountId }
    if (hasDiscount) {
        return product
    }
    
    kotlinx.coroutines.delay(5)  // Window for race condition
    
    val updatedDiscounts = product.discounts + discount
    val updatedProduct = product.copy(discounts = updatedDiscounts)
    return save(updatedProduct)  // Write
}
```

Between the check and write, concurrent threads could both read the product, verify the discount isn't present, and then both write it.

**Corrected Implementation:**
```kotlin
suspend fun applyDiscount(productId: String, discount: Discount): Product? {
    val filter = Filters.eq("id", productId)
    
    val updated = collection.findOneAndUpdate(
        filter,
        Updates.addToSet("discounts", discount),
        FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
    )
    
    return updated
}
```

MongoDB's `$addToSet` operator is atomic at the database level. It only adds the element if it's not already present in the array, eliminating the race condition entirely.

**Test Coverage:**
- should handle concurrent discount applications safely - NO DUPLICATES
- should be idempotent when applying same discount twice sequentially
- should apply discount to product correctly
- should return null when applying discount to non-existent product

---

## Bug 3: Missing JSON Content Serialization

**Locations:** 
- `src/main/kotlin/io/nexure/discount/Application.kt`
- `app/build.gradle.kts`

The Ktor application wasn't configured for JSON serialization. Additionally, the Kotlin serialization compiler plugin wasn't enabled in the build configuration.

**Issue:** HTTP endpoints returned serialization errors because no ContentNegotiation was installed.

**Fix in Application.kt:**
```kotlin
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.application.install

// In Application.module():
install(ContentNegotiation) {
    json()
}
```

**Fix in build.gradle.kts:**
```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    kotlin("plugin.serialization") version "2.2.20"
    application
}
```

The serialization plugin is required to process `@Serializable` annotations at compile time. Without it, the generated serializers aren't created.

**Test Coverage:**
- should return 400 when country parameter is missing
- GET products endpoint should return JSON
- PUT discount endpoint should accept and return JSON

---

## Test Results

All 12 tests pass:

**HttpEndpointTests**
- should return 400 when country parameter is missing
- GET products endpoint should return JSON
- PUT discount endpoint should accept and return JSON

**ProductServiceTests**
- should correctly apply VAT rates for different countries
- should return empty list for country with no products
- should calculate final price correctly with multiple discounts
- should handle concurrent discount applications safely - NO DUPLICATES
- should apply discount to product correctly
- should handle products from unknown countries
- should calculate final price correctly with single discount
- should be idempotent when applying same discount twice sequentially
- should return null when applying discount to non-existent product

---

## Files Changed

1. `src/main/kotlin/io/nexure/discount/Application.kt` - Added JSON content negotiation
2. `src/main/kotlin/io/nexure/discount/service/ProductService.kt` - Fixed discount calculation logic
3. `src/main/kotlin/io/nexure/discount/repository/ProductRepository.kt` - Fixed concurrency issue
4. `app/build.gradle.kts` - Added serialization compiler plugin

---

## Verification

```bash
cd discount
./gradlew test
```

Expected: BUILD SUCCESSFUL - 12 tests, 0 failures
