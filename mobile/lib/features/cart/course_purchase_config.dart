/// Client-side opt-in for the Course purchase screens.
/// Backend Cart, Checkout, and VNPay flags must be enabled separately.
const bool coursePurchaseUiEnabled = bool.fromEnvironment(
  'COURSE_PURCHASE_ENABLED',
  defaultValue: false,
);
