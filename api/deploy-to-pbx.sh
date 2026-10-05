#!/usr/bin/env bash
# Deploy softphone BFF to a FreePBX 17+ host.
#   SOFTPHONE_PBX_HOST=pbx.example.com ./deploy-to-pbx.sh
# Auth: SSH key (SOFTPHONE_SSH_KEY) or SSHPASS.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
HOST="${SOFTPHONE_PBX_HOST:-${IHF_PBX_HOST:-}}"
if [[ -z "$HOST" ]]; then
  echo "Set SOFTPHONE_PBX_HOST=pbx.example.com" >&2
  exit 1
fi
REMOTE_DIR="/var/www/html/ihf-softphone"
SSH_KEY="${SOFTPHONE_SSH_KEY:-${IHF_SSH_KEY:-}}"

SSH=(ssh -o StrictHostKeyChecking=accept-new -o ConnectTimeout=25)
# Prefer key when present. Password auth only when SOFTPHONE_USE_SSHPASS=1.
if [[ -n "$SSH_KEY" && -f "$SSH_KEY" && "${SOFTPHONE_USE_SSHPASS:-${IHF_USE_SSHPASS:-0}}" != "1" ]]; then
  SSH=(ssh -i "$SSH_KEY" -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new -o ConnectTimeout=25)
elif [[ -n "${SSHPASS:-${SSHPASS_PBX:-}}" ]]; then
  export SSHPASS="${SSHPASS:-${SSHPASS_PBX}}"
  SSH=(sshpass -e "${SSH[@]}")
else
  echo "Warn: no key at ${SSH_KEY} and no SSHPASS; relying on ssh-agent." >&2
fi

echo "Deploying to root@${HOST}:${REMOTE_DIR}"
"${SSH[@]}" "root@${HOST}" "mkdir -p '${REMOTE_DIR}' /var/spool/asterisk/ihf-softphone && chown asterisk:asterisk /var/spool/asterisk/ihf-softphone && chmod 0750 /var/spool/asterisk/ihf-softphone"

TMP=$(mktemp)
tar czf "$TMP" -C "$ROOT" --exclude=config.php --exclude=.git .
"${SSH[@]}" "root@${HOST}" "tar xzf - -C '${REMOTE_DIR}'" < "$TMP"
rm -f "$TMP"

"${SSH[@]}" "root@${HOST}" bash -s <<REMOTE
set -e
cd ${REMOTE_DIR}
[[ -f config.php ]] || cp config.example.php config.php
ln -sfn public/index.php index.php
ln -sfn public/.htaccess .htaccess
chown -R asterisk:asterisk ${REMOTE_DIR} /var/spool/asterisk/ihf-softphone
chmod -R a+rX ${REMOTE_DIR}
chmod 0640 config.php
# Ensure Apache can follow PATH_INFO under /ihf-softphone/
curl -sk "https://127.0.0.1/ihf-softphone/index.php/v1/health" -H "Host: ${HOST}"
echo
REMOTE
