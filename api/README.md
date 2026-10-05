# Softphone BFF

HTTPS API on FreePBX 17+ wrapping User Manager and the `sms` module.

Clients send `X-IHF-Token: <token>`.

Install steps: [../docs/BFF_INSTALL.md](../docs/BFF_INSTALL.md).  
Copy `config.example.php` to `config.php` on the PBX. Do not commit `config.php`.

Example base: `https://pbx.example.com/ihf-softphone/index.php`

## Layout

```
api/
  public/index.php
  public/admin/          operator assign UI
  lib/
  bin/setup-um-admin-perm.php
  config.example.php
  deploy-to-pbx.sh
  enrol/_template.html
```

On the PBX: `/var/www/html/ihf-softphone/`  
Tokens: `/var/spool/asterisk/ihf-softphone/tokens.json`
