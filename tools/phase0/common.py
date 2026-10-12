"""Shared constants for the Phase 0 measurement scripts.

The categories exist only for this measurement; they are not product categories.
"""

# Category -> definition shown to Ornith when labeling.
CATEGORIES = {
    "chats": "a private messaging conversation (WhatsApp, Telegram, Instagram DMs, SMS) with message bubbles, not public comments",
    "study": "study material: notes, textbook pages, exam or practice questions, lecture slides, solutions",
    "tweets": "a text post on X/Twitter or a similar social app, including comment sections under a post (avatar, @handle, like counts)",
    "memes": "a meme or joke image meant to be funny",
    "animals": "the main subject is an animal (pets, wildlife), photo or drawing",
    "notes": "personal notes or lists written in a notes or to-do app (Keep, Notes, Notion), not a social post",
}

# Category -> the text a user would plausibly type as the collection name.
# This is what method A embeds as the collection's text vector.
CATEGORY_QUERIES = {
    "chats": "chats",
    "study": "study notes",
    "tweets": "tweets",
    "memes": "memes",
    "animals": "animals",
    "notes": "notes",
}

# Copied verbatim from CollectionMatcher.BACKGROUND_DESCRIPTIONS (keep in sync).
BACKGROUND_DESCRIPTIONS = [
    "meme or joke image",
    "chat or messaging conversation",
    "social media post",
    "study notes or textbook page",
    "exam question paper",
    "document or form",
    "receipt or payment",
    "ticket or booking",
    "source code",
    "video game screen",
    "map",
    "shopping product page",
    "phone settings screen",
    "news article",
    "video app screen",
    "photo of a person",
    "music player",
    "calendar or schedule",
    "email inbox",
    "home screen with app icons",
]

# Mirrors GemmaTokenizer.QUERY_PREFIX.
QUERY_PREFIX = "task: search result | query: "

EMBEDDING_DIM = 128

# Candidate tags for junk-review groups (contract across vision and text models).
JUNK_TAGS = {
    "code": "Shows a one-time password, verification code or login code (e.g. an SMS or app screen with a 4–8 digit code).",
    "status": "A transactional status update: order placed/shipped/out for delivery/delivered, payment successful/failed, refund, booking or reservation confirmed, bill or recharge done.",
    "time_bound": "Only useful until a specific date or time: a ticket, boarding pass, event, appointment, booking, coupon/offer with an end date, or deadline.",
    "accidental": "Probably captured by accident or holds nothing worth keeping: mostly blank or loading screen, home screen or launcher, lock screen, notification shade or keyboard covering the content, an error dialog, a half-loaded page.",
}

