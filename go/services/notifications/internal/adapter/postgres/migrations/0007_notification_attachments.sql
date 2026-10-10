-- S243: email attachment references (signed media links: [{assetId, fileName, contentType, url, expiresAt}]),
-- fetched by the dispatcher when the email is sent. NULL = no attachments.
ALTER TABLE notification ADD COLUMN attachments jsonb;
