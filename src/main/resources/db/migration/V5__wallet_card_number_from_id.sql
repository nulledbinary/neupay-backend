-- Rewrite every wallet's card_number to NEU-<user.id_number>.
-- The previous scheme generated random NEU-XXXX-XXXX-XXXX cards; the application
-- now derives the card deterministically from the user's institutional ID so the
-- two identifiers are always in lockstep.
--
-- The wallets.card_number column is UNIQUE; users.id_number is also UNIQUE, so
-- the derived NEU-<id_number> is unique by induction. This update is therefore
-- safe in a single statement.

UPDATE wallets w
   SET card_number = 'NEU-' || u.id_number
  FROM users u
 WHERE u.id = w.user_id
   AND w.card_number <> 'NEU-' || u.id_number;
