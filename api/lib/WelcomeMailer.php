<?php
/**
 * Welcome / enrol email via local postfix (sendmail).
 *
 * The message clients see is HTML: a button, not the raw setup URL.
 * A plain-text part is included for mail apps that do not show HTML.
 *
 * From / Reply-To / envelope (-f) use config.php mail_from (an identity the PBX may send as).
 * Branding is config too: mail_brand_line (small caps under the name), mail_footer_line, and
 * mail_emblem (file under the BFF directory, served next to /enrol/). Empty values are left out.
 */
class WelcomeMailer {
	private $from;
	private $fromName;
	private $brandLine;
	private $footerLine;
	private $emblem;

	public function __construct(
		string $from = 'notify@pbx.example.com',
		string $fromName = 'Softphone',
		string $brandLine = '',
		string $footerLine = '',
		string $emblem = ''
	) {
		$this->from = $from !== '' ? $from : 'notify@pbx.example.com';
		$this->fromName = $fromName !== '' ? $fromName : 'Softphone';
		$this->brandLine = trim($brandLine);
		$this->footerLine = trim($footerLine);
		// Relative path inside the BFF directory only; anything else is ignored.
		$emblem = ltrim(trim($emblem), '/');
		$this->emblem = preg_match('#^[A-Za-z0-9._/-]+\.(png|jpg|jpeg|gif)$#i', $emblem) && strpos($emblem, '..') === false
			&& is_file(dirname(__DIR__) . '/' . $emblem) ? $emblem : '';
	}

	/**
	 * @return array{ok:bool,error?:string,to?:string,subject?:string}
	 */
	public function sendWelcome(string $to, array $payload): array {
		$to = trim($to);
		if ($to === '' || !filter_var($to, FILTER_VALIDATE_EMAIL)) {
			return ['ok' => false, 'error' => 'invalid_email'];
		}

		$rendered = $this->render($payload);
		$ok = $this->sendRaw($to, $rendered['subject'], $rendered['text'], $rendered['html']);
		if (!$ok) {
			return ['ok' => false, 'error' => 'sendmail_failed', 'to' => $to, 'subject' => $rendered['subject']];
		}
		return ['ok' => true, 'to' => $to, 'subject' => $rendered['subject']];
	}

	/**
	 * @return array{subject:string,text:string,html:string}
	 */
	public function render(array $payload): array {
		$ext = (string)($payload['extension'] ?? '');
		$label = (string)($payload['label'] ?? ($ext !== '' ? "ext $ext" : 'your line'));
		$enrol = (string)($payload['enrol_url'] ?? '');
		$install = (string)($payload['install_url'] ?? '');
		$expires = (string)($payload['expires_iso'] ?? '');
		$did = (string)($payload['did'] ?? '');
		$extraLines = is_array($payload['extra_lines'] ?? null) ? $payload['extra_lines'] : [];

		return [
			'subject' => $this->fromName . " — Set up $label",
			'text' => $this->buildText($label, $ext, $did, $enrol, $install, $expires, $extraLines),
			'html' => $this->buildHtml($label, $ext, $did, $enrol, $install, $expires, $extraLines),
		];
	}

	private function buildText(
		string $label,
		string $ext,
		string $did,
		string $enrol,
		string $install,
		string $expires,
		array $extraLines
	): string {
		$name = $this->fromName;
		$lines = [];
		$lines[] = "$name";
		$lines[] = '';
		$lines[] = "Your phone line is ready ($label).";
		$lines[] = 'Open this email and choose Set up your phone.';
		$lines[] = '';
		if ($ext !== '') {
			$lines[] = "Extension: $ext";
		}
		if ($did !== '') {
			$lines[] = "Number: +$did";
		}
		$when = $this->friendlyExpiry($expires);
		if ($when !== '') {
			$lines[] = "This button works once, until $when.";
		}
		$lines[] = '';
		$lines[] = 'If you do not see the button, open this link:';
		$lines[] = $enrol;
		if ($extraLines !== []) {
			$lines[] = '';
			$lines[] = 'Other lines on this account:';
			foreach ($extraLines as $row) {
				if (!is_array($row)) {
					continue;
				}
				$url = trim((string)($row['enrol_url'] ?? ''));
				if ($url === '') {
					continue;
				}
				$elabel = trim((string)($row['label'] ?? ''));
				$eext = trim((string)($row['extension'] ?? ''));
				$title = $elabel !== '' ? $elabel : ($eext !== '' ? "ext $eext" : 'another line');
				$lines[] = "Set up $title: $url";
			}
		}
		$lines[] = '';
		$lines[] = 'No password in this email.';
		if ($install !== '') {
			$lines[] = '';
			$lines[] = 'Need the app first?';
			$lines[] = $install;
		}
		$lines[] = '';
		$lines[] = "— $name";
		return implode("\n", $lines) . "\n";
	}

	private function buildHtml(
		string $label,
		string $ext,
		string $did,
		string $enrol,
		string $install,
		string $expires,
		array $extraLines
	): string {
		$name = $this->h($this->fromName);
		$heading = $this->h($label);
		$details = '';
		if ($ext !== '') {
			$details .= '<p style="margin:0 0 4px;font-size:15px;color:#eef2f8;">Extension ' . $this->h($ext) . '</p>';
		}
		if ($did !== '') {
			$details .= '<p style="margin:0 0 4px;font-size:15px;color:#eef2f8;">Number +' . $this->h($did) . '</p>';
		}
		$when = $this->friendlyExpiry($expires);
		$expiry = $when !== ''
			? '<p style="margin:8px 0 0;font-size:13px;line-height:1.4;color:#8491ad;">Works once, until ' . $this->h($when) . '.</p>'
			: '';
		$mark = $this->emblemUrl($enrol);
		$markHtml = $mark !== ''
			? '<img src="' . $mark . '" width="42" height="36" alt="" style="display:block;border:0;outline:none;"/>'
			: '';
		$brandHtml = $this->brandLine !== ''
			? '<p style="margin:2px 0 0;font-size:11px;letter-spacing:0.14em;font-weight:700;color:#d4af37;">' . $this->h(strtoupper($this->brandLine)) . '</p>'
			: '';
		$footerHtml = $this->footerLine !== ''
			? '<p style="margin:0 0 8px;font-size:13px;color:#8491ad;">' . $this->h($this->footerLine) . '</p>'
			: '';
		$primary = $this->button($enrol, 'Set up your phone');
		$extraHtml = '';
		foreach ($extraLines as $row) {
			if (!is_array($row)) {
				continue;
			}
			$url = trim((string)($row['enrol_url'] ?? ''));
			if ($url === '') {
				continue;
			}
			$elabel = trim((string)($row['label'] ?? ''));
			$eext = trim((string)($row['extension'] ?? ''));
			$title = $elabel !== '' ? $elabel : ($eext !== '' ? "ext $eext" : 'another line');
			$extraHtml .= $this->button($url, 'Set up ' . $title, true);
		}
		$extraBlock = $extraHtml !== ''
			? '<p style="margin:20px 0 0;font-size:14px;color:#aab6cc;">Other lines on this account</p>' . $extraHtml
			: '';
		$installBlock = '';
		if ($this->httpsHref($install) !== '') {
			$installBlock = '<p style="margin:24px 0 0;font-size:14px;color:#aab6cc;">Don\'t have the app yet?</p>'
				. $this->button($install, 'Get the app', true);
		}

		return <<<HTML
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1"/>
<title>{$name}</title>
</head>
<body style="margin:0;padding:0;background:#040914;">
<table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" bgcolor="#040914" style="background:#040914;">
<tr><td align="center" style="padding:28px 16px;">
<table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" bgcolor="#0b132b" style="max-width:520px;background:#0b132b;border-radius:18px;">
<tr><td style="padding:28px 28px 12px;font-family:Segoe UI,Helvetica,Arial,sans-serif;">
<table role="presentation" cellspacing="0" cellpadding="0" border="0"><tr>
<td style="padding-right:12px;vertical-align:middle;">{$markHtml}</td>
<td style="vertical-align:middle;">
<p style="margin:0;font-family:Georgia,Times New Roman,serif;font-size:22px;color:#eef2f8;">{$name}</p>
{$brandHtml}
</td></tr></table>
<h1 style="margin:26px 0 8px;font-family:Georgia,Times New Roman,serif;font-size:32px;line-height:1.15;font-weight:400;color:#eef2f8;">Your phone line<br/>is ready.</h1>
<p style="margin:0 0 8px;font-size:16px;line-height:1.45;color:#aab6cc;">{$heading}</p>
{$details}
{$primary}
<p style="margin:0;font-size:15px;line-height:1.45;color:#aab6cc;">Open this email and choose the gold button. On this PC it opens {$name}.</p>
{$expiry}
{$extraBlock}
{$installBlock}
<p style="margin:28px 0 4px;font-size:13px;color:#8491ad;">No password in this email.</p>
{$footerHtml}
</td></tr>
</table>
</td></tr>
</table>
</body>
</html>
HTML;
	}

	private function button(string $url, string $label, bool $secondary = false): string {
		$href = $this->httpsHref($url);
		if ($href === '') {
			return '';
		}
		$bg = $secondary ? '#142040' : '#d4af37';
		$color = $secondary ? '#eef2f8' : '#0b132b';
		$border = $secondary ? 'border:1px solid #233052;' : '';
		$text = $this->h($label);
		return '<table role="presentation" cellspacing="0" cellpadding="0" border="0" style="margin:16px 0;">'
			. '<tr><td bgcolor="' . $bg . '" style="border-radius:10px;' . $border . '">'
			. '<a href="' . $href . '" style="display:inline-block;padding:14px 28px;font-family:Segoe UI,Helvetica,Arial,sans-serif;font-size:16px;line-height:20px;color:' . $color . ';text-decoration:none;font-weight:700;">'
			. $text . '</a></td></tr></table>';
	}

	/** Absolute URL of the configured emblem, next to the enrol page; '' when none is configured. */
	private function emblemUrl(string $enrol): string {
		if ($this->emblem === '' || !preg_match('#^(https://[^/\s]+/ihf-softphone)/enrol/#i', $enrol, $match)) {
			return '';
		}
		return $this->h($match[1] . '/' . $this->emblem);
	}

	private function httpsHref(string $url): string {
		$url = trim($url);
		if (!preg_match('#^https://#i', $url) || preg_match('/[\r\n\s]/', $url)) {
			return '';
		}
		return htmlspecialchars($url, ENT_QUOTES | ENT_HTML5, 'UTF-8');
	}

	private function h(string $value): string {
		return htmlspecialchars($value, ENT_QUOTES | ENT_HTML5, 'UTF-8');
	}

	private function friendlyExpiry(string $iso): string {
		$iso = trim($iso);
		if ($iso === '') {
			return '';
		}
		$ts = strtotime($iso);
		if ($ts === false) {
			return '';
		}
		return gmdate('M j, Y g:i A', $ts) . ' UTC';
	}

	private function sendRaw(string $to, string $subject, string $text, string $html): bool {
		$from = $this->from;
		$fromName = $this->fromName;
		$boundary = 'ihf_' . bin2hex(random_bytes(12));
		$headers = [
			'From: ' . $this->encodeAddress($fromName, $from),
			'Reply-To: ' . $from,
			'MIME-Version: 1.0',
			'Content-Type: multipart/alternative; boundary="' . $boundary . '"',
			'X-Mailer: ihf-softphone-admin',
		];
		$body = $this->multipartBody($boundary, $text, $html);
		$msg = 'To: ' . $to . "\n"
			. 'Subject: ' . $this->encodeHeader($subject) . "\n"
			. implode("\n", $headers) . "\n\n"
			. $body;

		$descriptors = [
			0 => ['pipe', 'r'],
			1 => ['pipe', 'w'],
			2 => ['pipe', 'w'],
		];
		$cmd = '/usr/sbin/sendmail -t -i -f ' . escapeshellarg($from);
		$proc = @proc_open($cmd, $descriptors, $pipes);
		if (!is_resource($proc)) {
			return @mail($to, $subject, $body, implode("\r\n", $headers), '-f' . $from);
		}
		fwrite($pipes[0], $msg);
		fclose($pipes[0]);
		fclose($pipes[1]);
		fclose($pipes[2]);
		$code = proc_close($proc);
		return $code === 0;
	}

	private function multipartBody(string $boundary, string $text, string $html): string {
		return "--$boundary\r\n"
			. "Content-Type: text/plain; charset=UTF-8\r\n"
			. "Content-Transfer-Encoding: 8bit\r\n\r\n"
			. $text . "\r\n"
			. "--$boundary\r\n"
			. "Content-Type: text/html; charset=UTF-8\r\n"
			. "Content-Transfer-Encoding: 8bit\r\n\r\n"
			. $html . "\r\n"
			. "--$boundary--\r\n";
	}

	private function encodeAddress(string $name, string $email): string {
		$name = trim(preg_replace('/[\r\n]+/', ' ', $name) ?? $name);
		if ($name === '') {
			return $email;
		}
		return sprintf('%s <%s>', $this->encodeHeader($name), $email);
	}

	private function encodeHeader(string $value): string {
		$value = preg_replace('/[\r\n]+/', ' ', $value) ?? $value;
		if (preg_match('/[^\x20-\x7E]/', $value)) {
			return '=?UTF-8?B?' . base64_encode($value) . '?=';
		}
		return $value;
	}
}
