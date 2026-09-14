# Create / update a Smart Rice login account (BCrypt hash into MySQL user_account).
# Use create-user.cmd so Windows execution policy does not block this file:
#   .\create-user.cmd -Username zhangsan -Password "Secret#123" -DisplayName "Zhang San"
# Service account (no web login, no password, mapped by the server only):
#   .\create-user.cmd -Username astrbot -DisplayName "AstrBot Bot" -NoLogin
[CmdletBinding()]
param(
	[Parameter(Position = 0)]
	[string]$Username,

	[Parameter(Position = 1)]
	[string]$Password,

	[Parameter(Position = 2)]
	[string]$DisplayName,

	[Parameter(Position = 3)]
	[string]$Role,

	[switch]$Update,
	[switch]$Disabled,
	[switch]$NoLogin,
	[switch]$Help
)

$ErrorActionPreference = "Stop"
Set-Location -LiteralPath $PSScriptRoot

if ($Help) {
	& .\mvnw.cmd -q -DskipTests compile exec:java "-Dexec.args=--help"
	exit $LASTEXITCODE
}

if ([string]::IsNullOrWhiteSpace($Username)) {
	Write-Error "Missing -Username. Example: .\create-user.cmd -Username zhangsan -Password 'Secret#123' -DisplayName 'Zhang San'"
}

if ($NoLogin -and -not [string]::IsNullOrEmpty($Password)) {
	Write-Error "-NoLogin service accounts take no password. Remove -Password."
}

if ((-not $NoLogin) -and [string]::IsNullOrEmpty($Password)) {
	$secure = Read-Host "Password (hidden)" -AsSecureString
	$confirm = Read-Host "Password again" -AsSecureString
	$bstr1 = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
	$bstr2 = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($confirm)
	try {
		$plain1 = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr1)
		$plain2 = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr2)
		if ($plain1 -ne $plain2) {
			Write-Error "Passwords do not match"
		}
		$Password = $plain1
	} finally {
		[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr1)
		[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr2)
	}
}

$env:CREATE_USER_USERNAME = $Username.Trim()
if ($NoLogin) {
	Remove-Item Env:CREATE_USER_PASSWORD -ErrorAction SilentlyContinue
} else {
	$env:CREATE_USER_PASSWORD = $Password
}
if (-not [string]::IsNullOrWhiteSpace($DisplayName)) {
	$env:CREATE_USER_DISPLAY_NAME = $DisplayName.Trim()
} else {
	Remove-Item Env:CREATE_USER_DISPLAY_NAME -ErrorAction SilentlyContinue
}
if (-not [string]::IsNullOrWhiteSpace($Role)) {
	$env:CREATE_USER_ROLE = $Role.Trim()
} else {
	# Let the Java tool pick the default: ADMIN normally, SERVICE with -NoLogin.
	Remove-Item Env:CREATE_USER_ROLE -ErrorAction SilentlyContinue
}
$env:CREATE_USER_UPDATE = $(if ($Update) { "true" } else { "false" })
$env:CREATE_USER_ENABLED = $(if ($Disabled) { "false" } else { "true" })
$env:CREATE_USER_LOGIN_ENABLED = $(if ($NoLogin) { "false" } else { "true" })

try {
	& .\mvnw.cmd -q -DskipTests compile exec:java
	exit $LASTEXITCODE
} finally {
	Remove-Item Env:CREATE_USER_USERNAME -ErrorAction SilentlyContinue
	Remove-Item Env:CREATE_USER_PASSWORD -ErrorAction SilentlyContinue
	Remove-Item Env:CREATE_USER_DISPLAY_NAME -ErrorAction SilentlyContinue
	Remove-Item Env:CREATE_USER_ROLE -ErrorAction SilentlyContinue
	Remove-Item Env:CREATE_USER_UPDATE -ErrorAction SilentlyContinue
	Remove-Item Env:CREATE_USER_ENABLED -ErrorAction SilentlyContinue
	Remove-Item Env:CREATE_USER_LOGIN_ENABLED -ErrorAction SilentlyContinue
}
