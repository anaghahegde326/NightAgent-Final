- [ ] Locate SOSManager, ContactManager (emergency contacts), and location retrieval logic
- [ ] Confirm minimal edit scope (only SOSManager.kt)
- [ ] Update SOSManager SMS message to required exact format (with/without location)
- [ ] Use SmsManager to send to all contacts with safe permission handling and crash guards
- [ ] If SMS fails, show Toast error only
- [ ] Ensure SOS flow (state/timing/firestore/live tracking) remains unchanged
- [ ] Build: ./gradlew :app:assembleDebug

