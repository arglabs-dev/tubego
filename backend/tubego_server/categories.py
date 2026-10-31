"""Source-provided categories, retained with private resource metadata."""
import unicodedata

UNCATEGORIZED = ''


def normalize(raw):
    if not isinstance(raw, str):
        return UNCATEGORIZED
    # Bound external metadata and remove invisible/control characters.
    text = ' '.join(unicodedata.normalize('NFKC', raw).split())
    text = ''.join(c for c in text if not unicodedata.category(c).startswith('C'))
    return ' '.join(text.split())[:80].strip()


def from_metadata(info):
    categories = info.get('categories')
    if isinstance(categories, (list, tuple)):
        for raw in categories:
            category = normalize(raw)
            if category:
                return category
    return UNCATEGORIZED


# Missing metadata (including pre-feature resources) belongs to the empty category.
SQL = """COALESCE((SELECT json_extract(s.value_json,'$') FROM settings s
    WHERE s.scope='resource' AND s.owner_id=r.id AND s.key='category'),'')"""


def available(conn, user_id):
    return [row[0] for row in conn.execute(
        f'SELECT DISTINCT {SQL} AS category FROM resources r WHERE r.user_id=? ORDER BY category COLLATE NOCASE',
        (user_id,)).fetchall()]
