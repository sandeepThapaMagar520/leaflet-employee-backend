function doPost(e) {
  try {
    const payload = JSON.parse(e.postData.contents || "{}");
    const expectedSecret =
      PropertiesService.getScriptProperties().getProperty("WEBHOOK_SECRET");

    if (!expectedSecret || payload.secret !== expectedSecret) {
      return jsonResponse({ success: false, error: "Unauthorized" });
    }

    const to = String(payload.to || "").trim();
    const subject = String(payload.subject || "").trim();
    const body = String(payload.body || "");
    const htmlBody = String(payload.htmlBody || "");
    const fromName = String(payload.fromName || "Leaflet EMS").trim();
    const idempotencyKey = String(payload.idempotencyKey || "").trim();

    if (!to || !subject || !body || !idempotencyKey) {
      return jsonResponse({
        success: false,
        error: "Missing required delivery field"
      });
    }

    const lock = LockService.getScriptLock();
    if (!lock.tryLock(10000)) {
      return jsonResponse({ success: false, retryable: true, error: "Busy" });
    }

    try {
      const cache = CacheService.getScriptCache();
      if (cache.get("delivery:" + idempotencyKey)) {
        return jsonResponse({ success: true, duplicate: true, messageId: idempotencyKey });
      }

    const message = {
      to: to,
      subject: subject,
      body: body,
      name: fromName
    };

    if (htmlBody) {
      message.htmlBody = htmlBody;
    }

      MailApp.sendEmail(message);
      // Apps Script does not expose Gmail's provider message ID. Retain the
      // caller's delivery identity for the longest CacheService window so a
      // timed-out retry is suppressed during the normal retry horizon.
      cache.put("delivery:" + idempotencyKey, "accepted", 21600);

      return jsonResponse({
        success: true,
        messageId: idempotencyKey,
        remainingQuota: MailApp.getRemainingDailyQuota()
      });
    } finally {
      lock.releaseLock();
    }
  } catch (error) {
    return jsonResponse({ success: false, retryable: true, error: "Email delivery failed" });
  }
}

function doGet() {
  return jsonResponse({
    success: true,
    service: "Leaflet EMS Mail Service"
  });
}

function jsonResponse(data) {
  return ContentService
    .createTextOutput(JSON.stringify(data))
    .setMimeType(ContentService.MimeType.JSON);
}
