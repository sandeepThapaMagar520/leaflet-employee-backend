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

    if (!to || !subject || !body) {
      return jsonResponse({
        success: false,
        error: "Missing to, subject, or body"
      });
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

    return jsonResponse({
      success: true,
      remainingQuota: MailApp.getRemainingDailyQuota()
    });
  } catch (error) {
    console.error(error);
    return jsonResponse({ success: false, error: "Email delivery failed" });
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
