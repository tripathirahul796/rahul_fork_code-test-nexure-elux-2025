package io.nexure.discount.service

import io.nexure.discount.config.VatConfig
import io.nexure.discount.model.Discount
import io.nexure.discount.model.DiscountResponse
import io.nexure.discount.model.Product
import io.nexure.discount.model.ProductResponse
import io.nexure.discount.repository.ProductRepository

class ProductService(private val repository: ProductRepository) {

    suspend fun getProductsByCountry(country: String): List<ProductResponse> =
        repository.findByCountry(country).map { it.toProductResponse() }

    suspend fun applyDiscount(productId: String, discount: Discount): ProductResponse? =
        repository.applyDiscount(productId, discount)?.toProductResponse()

    private fun Product.toProductResponse(): ProductResponse = ProductResponse(
        id = id,
        name = name,
        basePrice = basePrice,
        country = country,
        discounts = discounts.map { DiscountResponse(it.discountId, it.percent) },
        finalPrice = calculateFinalPrice()
    )

    private fun Product.calculateFinalPrice(): Double {
        val vatRate = VatConfig.getVatRate(country)
        // Apply discounts multiplicatively: (1 - d1) * (1 - d2) * ... 
        val discountMultiplier = discounts.fold(1.0) { acc, discount ->
            acc * (1 - discount.percent / 100.0)
        }
        return basePrice * discountMultiplier * (1 + vatRate)
    }
}
