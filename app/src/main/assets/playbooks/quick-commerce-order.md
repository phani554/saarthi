---
id: quick_commerce_order
name: Order Items from Quick Commerce App
triggers:
  - "blinkit"
  - "zepto"
  - "instamart"
  - "grofers"
  - "order from"
  - "buy from"
  - "items from"
---

When the user asks to order multiple items from Blinkit, Zepto, Instamart, or another quick-commerce app:

1. **open_app** → call open_app(package_name="com.grofers.customerapp") (or "blinkit")
2. **For each item in the list**:
   a. **get_screen_info** → inspect the screen for the search bar or search icon.
   b. **input_text** → type the item name into the search bar.
   c. **get_screen_info** → inspect search results.
   d. **find_and_tap** / **tap_node** → tap the "ADD" or "+" button on the best matching product.
   e. Clear search bar or tap back to return to search for the next item.
3. **Open Cart** → once all items have been added to the cart, tap "View Cart" or the Cart icon.
4. **finish** → call finish(summary="Added [items] to your cart on Blinkit. Please review your cart and proceed to checkout.")

Safety rule: Always stop at the Cart / Checkout page. Do NOT automatically confirm payment or place the order.
