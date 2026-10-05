<?php

class Response {
	public static function json(int $code, array $payload): void {
		http_response_code($code);
		header('Content-Type: application/json; charset=utf-8');
		echo json_encode($payload, JSON_UNESCAPED_SLASHES);
		exit;
	}

	public static function jsonBody(): array {
		$raw = file_get_contents('php://input') ?: '';
		if ($raw === '') {
			return $_POST ?: [];
		}
		$data = json_decode($raw, true);
		return is_array($data) ? $data : [];
	}
}
