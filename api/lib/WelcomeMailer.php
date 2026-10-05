<?php
/**
 * Plain-text welcome / enrol email via local postfix (sendmail).
 *
 * From / Reply-To / envelope (-f) use config.php mail_from.
 * Example: notify@pbx.example.com
 */
class WelcomeMailer {
	private $from;
	private $fromName;

	public function __construct(string $from = 'notify@pbx.example.com', string $fromName = 'Softphone') {
		$this->from = $from !== '' ? $from : 'notify@pbx.example.com';
		$this->fromName = $fromName !== '' ? $fromName : 'Softphone';
	}

	/**
	 * @return array{ok:bool,error?:string,to?:string,subject?:string}
	 */
	public function sendWelcome(string $to, array $payload): array {
		$to = trim($to);
		if ($to === '' || !filter_var($to, FILTER_VALIDATE_EMAIL)) {
			return ['ok' => false, 'error' => 'invalid_email'];
		}

		$ext = (string)($payload['extension'] ?? '');
		$label = (string)($payload['label'] ?? ("ext $ext"));
		$enrol = (string)($payload['enrol_url'] ?? '');
		$install = (string)($payload['install_url'] ?? '');
		$expires = (string)($payload['expires_iso'] ?? '');
		$did = (string)($payload['did'] ?? '');
		$deep = (string)($payload['deep_link'] ?? '');
		$extraLines = is_array($payload['extra_lines'] ?? null) ? $payload['extra_lines'] : [];

		$subject = $this->fromName . " — Set up $label";
		$body = $this->buildBody($label, $ext, $did, $enrol, $deep, $install, $expires, $extraLines);

		$ok = $this->sendRaw($to, $subject, $body);
		if (!$ok) {
			return ['ok' => false, 'error' => 'sendmail_failed', 'to' => $to, 'subject' => $subject];
		}
		return ['ok' => true, 'to' => $to, 'subject' => $subject];
	}

	private function buildBody(
		string $label,
		string $ext,
		string $did,
		string $enrol,
		string $deep,
		string $install,
		string $expires,
		array $extraLines = []
	): string {
		$didLine = $did !== '' ? "DID: +$did\n" : '';
		$expLine = $expires !== '' ? "This setup link works once and expires: $expires UTC\n" : '';
		$extraBlock = '';
		if ($extraLines !== []) {
			$lines = [];
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
				$edid = trim((string)($row['did'] ?? ''));
				$name = $elabel !== '' ? $elabel : ($eext !== '' ? "ext $eext" : 'another line');
				$didBit = $edid !== '' ? " (DID +$edid)" : '';
				$lines[] = "- $name$didBit\n  $url";
			}
			if ($lines !== []) {
				$extraBlock = "\nAdditional line(s) on this account — open on the same phone to add a second line:\n"
					. implode("\n", $lines) . "\n";
			}
		}
		$name = $this->fromName;
		return <<<TXT
$name — setup link ($label)

Open this email on the phone where the softphone is installed, then tap Set up:

$enrol

Extension: $ext
{$didLine}{$expLine}{$extraBlock}
Already have one line set up? Open a link again (or Lines → Add second line → Email me a setup link) to enrol your next extension — up to two per phone.

No password in this email. The link opens the app when it is installed.

If you still need the app:
$install

Alternate deep link: $deep
(Or paste the token from the HTTPS page under Advanced setup → Enrol line.)

— $name
TXT;
	}

	private function sendRaw(string $to, string $subject, string $body): bool {
		$from = $this->from;
		$fromName = $this->fromName;
		$headers = [
			'From: ' . $this->encodeAddress($fromName, $from),
			'Reply-To: ' . $from,
			'MIME-Version: 1.0',
			'Content-Type: text/plain; charset=UTF-8',
			'Content-Transfer-Encoding: 8bit',
			'X-Mailer: ihf-softphone-admin',
		];
		$envelope = 'From: ' . $from . "\n";
		$msg = 'To: ' . $to . "\n"
			. 'Subject: ' . $this->encodeHeader($subject) . "\n"
			. implode("\n", $headers) . "\n\n"
			. $body . "\n";

		$descriptors = [
			0 => ['pipe', 'r'],
			1 => ['pipe', 'w'],
			2 => ['pipe', 'w'],
		];
		$cmd = '/usr/sbin/sendmail -t -i -f ' . escapeshellarg($from);
		$proc = @proc_open($cmd, $descriptors, $pipes);
		if (!is_resource($proc)) {
			// Fallback: PHP mail()
			return @mail($to, $subject, $body, implode("\r\n", $headers), '-f' . $from);
		}
		fwrite($pipes[0], $msg);
		fclose($pipes[0]);
		fclose($pipes[1]);
		fclose($pipes[2]);
		$code = proc_close($proc);
		return $code === 0;
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
