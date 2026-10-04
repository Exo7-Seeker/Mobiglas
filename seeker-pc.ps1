# Seeker PC — liaison Star Citizen -> mobiGlas d'Exo
# Lit le Game.log pendant que tu joues, garde uniquement les événements utiles (lieu, vaisseau, zones, notifications,
# morts/destructions, missions...) et les envoie à ton téléphone par un relais gratuit (ntfy.sh), sans compte ni clé.
# Rien n'est modifié dans le jeu. Ton pseudo, tes identifiants numériques et les adresses IP sont masqués avant l'envoi.
#
# Première utilisation (le code vient de l'appli : réglages de Seeker -> Liaison avec le jeu -> Générer le code) :
#   powershell -ExecutionPolicy Bypass -File .\seeker-pc.ps1 -Code VOTRECODE
# Les fois suivantes, le code est retenu :
#   powershell -ExecutionPolicy Bypass -File .\seeker-pc.ps1
#
# Options :
#   -Chemin "B:\StarCitizen\LIVE\Game.log"   si le fichier n'est pas trouvé tout seul
#   -Rejouer "chemin\Game.log"               montre ce qui SERAIT envoyé depuis un ancien fichier (rien n'est envoyé)
#   -Serveur "https://ntfy.sh"               autre serveur ntfy si besoin
#
# Fichiers écrits localement (dossier %APPDATA%\SeekerPC) :
#   types.txt     recensement de TOUS les types de lignes vus dans le Game.log (nombre + un exemple anonymisé), mis à jour toutes les minutes
#   inconnus.txt  lignes qui ressemblent à une mission / mort / quantum... mais que l'appli ne comprend pas encore
param(
  [string]$Code = "",
  [string]$Chemin = "",
  [string]$Rejouer = "",
  [string]$Serveur = "https://ntfy.sh"
)

$ErrorActionPreference = "Continue"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$Version = "3"

# ---------- filtres (expressions compilées : rapides même sur de gros fichiers) ----------
# Lignes gardées et envoyées (texte après l'horodatage)
$reKeep = '<SHUDEvent_OnNotification>|\] to queue\. New queue size|<RequestLocationInventory>|<Vehicle Control Flow>|<Join PU>|<SystemQuit>|Log started on|<Actor Death>|<Vehicle Destruction>|<Jump Drive|<Quantum|<Corpse|<Incapacitated|<Player Arrested|<Mission|<ObjectiveUpdate|<Contract[A-Z _]'
# Lignes qu'on ne veut surtout pas perdre si la file déborde
$reHaut = '<Actor Death>|<Vehicle Destruction>|<Jump Drive|<Quantum|<Corpse|<Incapacitated|<Player Arrested|<Mission|<ObjectiveUpdate|<Contract[A-Z _]|<Join PU>|<SystemQuit>|Log started on|<RequestLocationInventory>'
# Lignes "peut-être intéressantes" mais pas encore comprises : rangées dans un fichier local pour qu'on les étudie
$reInconnu = '<[^<>]*(mission|contract|objective|quantum|jump|death|destruction|killed|incap|arrest|crimestat|claim|respawn|insurance|bounty|wanted|reputation|stolen|refuel|salvage|mining|trade|commodity|purchase|shop|kiosk|loot|scan)[^<>]*>'
$reBruit = 'ContextEstablisher|ContractGenerator|Subsumption|ResolveSpawn|SpawnData|CDiscipline|ATC_Reset|InitializeSlot|LoadingPlatform|StatObjLoad|DockingTube|VehicleListQuery|OnRequestFetchVehicles|Inventory|LandingArea_|StowingVehicle'

$RO = [System.Text.RegularExpressions.RegexOptions]
$rxKeep = New-Object System.Text.RegularExpressions.Regex($reKeep, $RO::Compiled)
$rxHaut = New-Object System.Text.RegularExpressions.Regex($reHaut, $RO::Compiled)
$rxInc = New-Object System.Text.RegularExpressions.Regex($reInconnu, ($RO::Compiled -bor $RO::IgnoreCase))
$rxBruit = New-Object System.Text.RegularExpressions.Regex($reBruit, $RO::Compiled)
$rxTag = New-Object System.Text.RegularExpressions.Regex('^(?:\[[A-Za-z]+\]\s*)?(<[^<>]{2,50}>)', $RO::Compiled)
$rxNick = New-Object System.Text.RegularExpressions.Regex('nickname="([^"]{2,40})"', $RO::Compiled)
$rxIp = New-Object System.Text.RegularExpressions.Regex('\b\d{1,3}(\.\d{1,3}){3}\b', $RO::Compiled)
$rxId = New-Object System.Text.RegularExpressions.Regex('\b\d{10,}\b', $RO::Compiled)

$Pseudo = ""

function Nettoyer([string]$t) {
  $t = $rxIp.Replace($t, 'x.x.x.x')
  if ($script:Pseudo) { $t = $t.Replace($script:Pseudo, '{ME}') }
  $t = $t.Replace('00000000-0000-0000-0000-000000000000', 'ZEROGUID')
  $t = $rxId.Replace($t, '#')
  if ($t.Length -gt 600) { $t = $t.Substring(0, 600) }
  return $t
}

# corps de la ligne = texte après l'horodatage <2026-...Z>
function Corps([string]$l) {
  if ($l.Length -gt 1 -and $l[0] -eq '<') {
    $i = $l.IndexOf('>')
    if ($i -gt 0 -and $i -lt 40) { return $l.Substring($i + 1).TrimStart() }
  }
  return $l
}

# ---------- trouver le Game.log ----------
function Trouver-Log {
  if ($Chemin -and (Test-Path $Chemin)) { return $Chemin }
  $c = @(
    "B:\StarCitizen\LIVE\Game.log",
    "C:\Program Files\Roberts Space Industries\StarCitizen\LIVE\Game.log",
    "D:\Roberts Space Industries\StarCitizen\LIVE\Game.log",
    "E:\Roberts Space Industries\StarCitizen\LIVE\Game.log",
    "C:\Games\StarCitizen\LIVE\Game.log",
    "D:\Games\StarCitizen\LIVE\Game.log"
  )
  foreach ($x in $c) { if (Test-Path $x) { return $x } }
  return ""
}

# ---------- mode rejeu : montre ce qui serait envoyé ----------
if ($Rejouer) {
  if (-not (Test-Path $Rejouer)) { Write-Host "Fichier introuvable : $Rejouer"; exit }
  $nt = 0; $nk = 0; $nh = 0
  foreach ($l in [System.IO.File]::ReadLines($Rejouer)) {
    $nt++
    if (-not $Pseudo -and $l.IndexOf('nickname="') -ge 0) { $m = $rxNick.Match($l); if ($m.Success) { $Pseudo = $m.Groups[1].Value } }
    $b = Corps $l
    if ($rxKeep.IsMatch($b)) {
      $nk++
      if ($rxHaut.IsMatch($b)) { $nh++ }
      Write-Host (Nettoyer $l)
    }
  }
  Write-Host ""
  Write-Host "$nt lignes lues, $nk gardées ($nh prioritaires)." -ForegroundColor Green
  exit
}

# ---------- configuration ----------
$dossierCfg = Join-Path $env:APPDATA "SeekerPC"
if (-not (Test-Path $dossierCfg)) { New-Item -ItemType Directory -Path $dossierCfg | Out-Null }
$fichierCfg = Join-Path $dossierCfg "config.json"
if ($Code) {
  @{ code = $Code } | ConvertTo-Json | Set-Content -Path $fichierCfg -Encoding UTF8
} elseif (Test-Path $fichierCfg) {
  $Code = (Get-Content $fichierCfg -Raw | ConvertFrom-Json).code
}
if (-not $Code) {
  Write-Host "Il me faut le code de liaison. Dans l'appli : réglages de Seeker -> Liaison avec le jeu -> Générer le code."
  $Code = Read-Host "Colle le code ici"
  if ($Code) { @{ code = $Code } | ConvertTo-Json | Set-Content -Path $fichierCfg -Encoding UTF8 }
}
if (-not $Code) { Write-Host "Pas de code : arrêt."; exit }
$Code = $Code.Trim()
$Sujet = "mgl-" + $Code
$Url = "$Serveur/$Sujet"
$fichierInconnu = Join-Path $dossierCfg "inconnus.txt"
$fichierTypes = Join-Path $dossierCfg "types.txt"
$vus = @{}
$types = @{}
$exemple = @{}
$recents = @{}
$typesChange = $false

$log = Trouver-Log
if (-not $log) {
  Write-Host "Game.log introuvable. Relance avec -Chemin ""B:\...\LIVE\Game.log"""
  exit
}

# ---------- file d'envoi (avec priorité : si ça déborde, on sacrifie d'abord le moins important) ----------
$file = New-Object System.Collections.ArrayList
$prio = New-Object System.Collections.ArrayList
$dernierEnvoi = [DateTime]::MinValue
$nLues = 0
$nEnvoyees = 0
$nDoublons = 0
$MaxFile = 400
$bloqueJusqua = [DateTime]::MinValue
# le relais gratuit limite le nombre de messages par jour (environ 250) : on les compte et on les économise
$fichierQuota = Join-Path $dossierCfg "quota.json"
$jourQuota = (Get-Date).ToUniversalTime().ToString("yyyy-MM-dd")
$msgJour = 0
$LimiteDouce = 200
try { if (Test-Path $fichierQuota) { $q = Get-Content $fichierQuota -Raw | ConvertFrom-Json; if ($q.jour -eq $jourQuota) { $msgJour = [int]$q.n } } } catch { }
function Noter-Quota {
  $j = (Get-Date).ToUniversalTime().ToString("yyyy-MM-dd")
  if ($j -ne $script:jourQuota) { $script:jourQuota = $j; $script:msgJour = 0 }
  $script:msgJour++
  try { @{ jour = $script:jourQuota; n = $script:msgJour } | ConvertTo-Json | Set-Content -Path $fichierQuota -Encoding UTF8 } catch { }
}

function Envoyer([string]$texte) {
  try {
    $octets = [System.Text.Encoding]::UTF8.GetBytes($texte)
    Invoke-RestMethod -Uri $Url -Method Post -Body $octets -ContentType "text/plain; charset=utf-8" -TimeoutSec 15 -UseBasicParsing | Out-Null
    Noter-Quota
    return $true
  } catch {
    $msg = $_.Exception.Message
    Write-Host ("  (envoi impossible : " + $msg + ")") -ForegroundColor DarkYellow
    # le service gratuit limite les messages : en cas de refus (429), on s'arrête 10 min au lieu d'insister
    if ($msg -match '429') {
      $script:bloqueJusqua = (Get-Date).AddMinutes(10)
      Write-Host "  Limite du relais gratuit atteinte : nouvel essai dans 10 min. Les événements importants restent en attente." -ForegroundColor Yellow
    }
    return $false
  }
}

function Ajouter([string]$ligne, [int]$p) {
  [void]$file.Add($ligne)
  [void]$prio.Add($p)
  while ($file.Count -gt $MaxFile) {
    $i = $prio.IndexOf(2)
    if ($i -lt 0) { $i = 0 }
    $file.RemoveAt($i)
    $prio.RemoveAt($i)
  }
}

function Vider-File {
  if ($file.Count -eq 0) { return }
  if ((Get-Date) -lt $script:bloqueJusqua) { return }
  $script:dernierEnvoi = Get-Date
  $lot = New-Object System.Text.StringBuilder
  while ($file.Count -gt 0) {
    [void]$lot.Clear()
    $nLot = 0
    $octets = 0
    # un message ntfy fait 4096 octets au maximum : on découpe à 3500
    while ($nLot -lt $file.Count) {
      $ligne = [string]$file[$nLot]
      $o = [System.Text.Encoding]::UTF8.GetByteCount($ligne) + 2
      if ($nLot -gt 0 -and ($octets + $o) -gt 3500) { break }
      [void]$lot.AppendLine($ligne)
      $octets += $o
      $nLot++
    }
    if (-not (Envoyer $lot.ToString())) { return }
    # on retire de la file seulement ce qui est parti : un échec ne provoque donc jamais de doublons
    $file.RemoveRange(0, $nLot)
    $prio.RemoveRange(0, $nLot)
    $script:nEnvoyees += $nLot
    $script:dernierEnvoi = Get-Date
    if ($file.Count -gt 0) { Start-Sleep -Milliseconds 800 }
  }
}

function Horo { return (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss.fffZ") }

function Ecrire-Types {
  try {
    $o = New-Object System.Collections.ArrayList
    [void]$o.Add("Recensement des lignes du Game.log (anonymisé) - " + (Get-Date).ToString("yyyy-MM-dd HH:mm"))
    [void]$o.Add("Types distincts : " + $types.Count + " - lignes lues : " + $script:nLues)
    [void]$o.Add("")
    foreach ($e in ($types.GetEnumerator() | Sort-Object -Property Value -Descending)) {
      [void]$o.Add("x" + $e.Value + "  " + $e.Key)
      if ($exemple.ContainsKey($e.Key)) { [void]$o.Add("      " + $exemple[$e.Key]) }
    }
    [System.IO.File]::WriteAllLines($fichierTypes, $o, [System.Text.Encoding]::UTF8)
    $script:typesChange = $false
  } catch { }
}

# ---------- traitement d'une ligne ----------
# $rattrapage : relecture du fichier existant au démarrage (on mémorise dans $tampon, on n'envoie rien tout de suite)
$tampon = New-Object System.Collections.ArrayList
$etatIdx = @{}

function Traiter([string]$l, [bool]$rattrapage) {
  if ($l.Length -eq 0) { return }
  if ($l[$l.Length - 1] -eq "`r") { $l = $l.Substring(0, $l.Length - 1); if ($l.Length -eq 0) { return } }
  $script:nLues++
  if ($script:Pseudo.Length -eq 0 -and $l.IndexOf('nickname="') -ge 0) {
    $m = $rxNick.Match($l)
    if ($m.Success) { $script:Pseudo = $m.Groups[1].Value }
  }
  $b = Corps $l

  # recensement des types de lignes (pour apprendre ce que le jeu écrit vraiment)
  $mt = $rxTag.Match($b)
  if ($mt.Success) { $cle = $mt.Groups[1].Value } else { $cle = '(sans balise)' }
  if ($types.ContainsKey($cle)) { $types[$cle]++ } elseif ($types.Count -lt 400) { $types[$cle] = 1 } else { $cle = $null }
  if ($cle) {
    $c = $types[$cle]
    if ($c -le 3 -or ($c % 250) -eq 0) { $exemple[$cle] = Nettoyer $l }
    $script:typesChange = $true
  }

  if ($rxKeep.IsMatch($b)) {
    $n = Nettoyer $l
    if ($rattrapage) {
      [void]$tampon.Add($n)
      $k = $null
      if ($b.Contains('RequestLocationInventory')) { $k = 'loc' }
      elseif ($b.Contains('joined channel')) { $k = 'ship' }
      elseif ($b.Contains('Armistice')) { $k = 'arm' }
      elseif ($b.Contains('Jurisdiction')) { $k = 'jur' }
      elseif ($b.Contains('Monitored Space')) { $k = 'mon' }
      elseif ($b.Contains('Log started on')) { $k = 'start' }
      elseif ($b.Contains('<Join PU>')) { $k = 'pu' }
      elseif ($b.Contains('ClearDriver')) { $k = 'drv' }
      if ($k) { $etatIdx[$k] = $tampon.Count - 1 }
    } else {
      # une même ligne répétée en rafale (ex. frontière d'une zone) n'est envoyée qu'une fois par 10 s
      if ($b.Length -gt 200) { $sig = $b.Substring(0, 200) } else { $sig = $b }
      $now = [DateTime]::UtcNow
      if ($recents.ContainsKey($sig) -and ($now - $recents[$sig]).TotalSeconds -lt 10) { $script:nDoublons++; return }
      if ($recents.Count -gt 300) { $recents.Clear() }
      $recents[$sig] = $now
      if ($rxHaut.IsMatch($b)) { $p = 3 } else { $p = 2 }
      Ajouter $n $p
      Write-Host ("  > " + $n.Substring(0, [Math]::Min(150, $n.Length))) -ForegroundColor Cyan
    }
  } elseif ($rxInc.IsMatch($b) -and -not $rxBruit.IsMatch($b)) {
    $sig = ($b -replace '[0-9]', '#')
    if ($sig.Length -gt 70) { $sig = $sig.Substring(0, 70) }
    if (-not $vus.ContainsKey($sig) -and $vus.Count -lt 1000) {
      $vus[$sig] = 1
      try { Add-Content -Path $fichierInconnu -Value (Nettoyer $l) -Encoding UTF8 } catch { }
    }
  }
}

# ---------- démarrage ----------
Write-Host "Seeker PC v$Version" -ForegroundColor Green
Write-Host "Fichier surveillé : $log"
Write-Host "Salon : $Sujet"
Write-Host "Laisse cette fenêtre ouverte pendant que tu joues. Ctrl+C pour arrêter."
Write-Host ""

[void](Envoyer ("$(Horo) [SeekerPC] hello v$Version"))

# Rattrapage : on relit le fichier actuel pour connaître la situation en cours
$position = 0
try {
  $fs0 = New-Object System.IO.FileStream($log, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]"ReadWrite,Delete")
  $sr0 = New-Object System.IO.StreamReader($fs0, [System.Text.Encoding]::UTF8)
  while (($l = $sr0.ReadLine()) -ne $null) { Traiter $l $true }
  $position = $fs0.Position
  $sr0.Close(); $fs0.Close()
} catch { Write-Host "Lecture initiale impossible : $($_.Exception.Message)" -ForegroundColor DarkYellow }

if ($tampon.Count -gt 0) {
  # on envoie les 40 dernières lignes utiles + le dernier état connu (lieu, vaisseau, zone, juridiction...) même s'il est plus ancien
  $sel = New-Object 'System.Collections.Generic.SortedSet[int]'
  $debut = [Math]::Max(0, $tampon.Count - 40)
  for ($i = $debut; $i -lt $tampon.Count; $i++) { [void]$sel.Add($i) }
  foreach ($v in $etatIdx.Values) {
    [void]$sel.Add([int]$v)
    if (([string]$tampon[$v]) -notmatch 'to queue' -and ($v + 1) -lt $tampon.Count) { [void]$sel.Add([int]$v + 1) }
  }
  foreach ($i in $sel) { Ajouter ([string]$tampon[$i]) 3 }
  Write-Host ("Situation en cours envoyée (" + $sel.Count + " lignes sur " + $tampon.Count + " utiles).") -ForegroundColor Green
  Vider-File
}
$tampon.Clear()
$script:nLues = 0
Ecrire-Types

$creation = (Get-Item $log).CreationTimeUtc
$decodeur = [System.Text.Encoding]::UTF8.GetDecoder()
$reste = ""
$dernierBattement = Get-Date
$dernierControle = Get-Date
$dernierStatut = Get-Date
$dernierTypes = Get-Date
$enRetard = $false
$derniereLecture = Get-Date
$avertiQuota = $false

try {
  while ($true) {
    # si on a du retard sur le fichier (chargement du jeu = énormément de lignes), on ne dort pas
    if (-not $enRetard) { Start-Sleep -Milliseconds 500 }
    $enRetard = $false
    $maintenant = Get-Date

    # nouveau Game.log (le jeu a été relancé) ou fichier tronqué : on repart du début
    if (($maintenant - $dernierControle).TotalSeconds -ge 3) {
      $dernierControle = $maintenant
      if (Test-Path $log) {
        $info = Get-Item $log
        if ($info.CreationTimeUtc -ne $creation -or $info.Length -lt $position) {
          Write-Host "Nouveau Game.log détecté : on repart du début." -ForegroundColor Green
          $creation = $info.CreationTimeUtc
          $position = 0
          $reste = ""
          $vus.Clear()
          $types.Clear()
          $exemple.Clear()
          $recents.Clear()
          $script:Pseudo = ""
          Ajouter "$(Horo) [SeekerPC] newlog" 3
        }
      }
    }

    if (Test-Path $log) {
      try {
        $fs = New-Object System.IO.FileStream($log, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]"ReadWrite,Delete")
        if ($fs.Length -gt $position) {
          $fs.Position = $position
          $tailleLue = [int][Math]::Min([long]4194304, [long]($fs.Length - $position))
          $buf = New-Object byte[] $tailleLue
          $lus = $fs.Read($buf, 0, $tailleLue)
          $position += $lus
          if ($lus -gt 0) { $derniereLecture = $maintenant }
          $chars = New-Object char[] ($lus + 4)
          $nc = $decodeur.GetChars($buf, 0, $lus, $chars, 0)
          $texte = $reste + ([System.String]::new($chars, 0, $nc))
          $morceaux = $texte -split "`n"
          if ($morceaux.Count -gt 0) {
            $reste = $morceaux[$morceaux.Count - 1]
            for ($i = 0; $i -lt $morceaux.Count - 1; $i++) { Traiter $morceaux[$i] $false }
          }
          if ($fs.Length -gt $position) { $enRetard = $true }
        }
        $fs.Close()
      } catch { }
    }

    # envoi groupé : toutes les 20 s au plus, 5 s si un événement important attend (mort, mission, quantum...)
    # quand le quota du jour approche, on espace à 60 s
    $delai = 20
    if ($prio.Contains(3)) { $delai = 5 }
    if ($msgJour -ge $LimiteDouce) { $delai = 60 }
    if ($file.Count -gt 0 -and ($maintenant - $dernierEnvoi).TotalSeconds -ge $delai) { Vider-File }

    # battement de cœur : toutes les 5 min pendant que le jeu écrit, toutes les 20 min sinon (économise le quota du relais)
    if (($maintenant - $derniereLecture).TotalMinutes -lt 10) { $delaiHb = 300 } else { $delaiHb = 1200 }
    if (($maintenant - $dernierBattement).TotalSeconds -ge $delaiHb -and $maintenant -ge $bloqueJusqua -and $msgJour -lt $LimiteDouce) {
      $dernierBattement = $maintenant
      [void](Envoyer ("$(Horo) [SeekerPC] hb"))
    }
    if ($msgJour -ge $LimiteDouce -and -not $avertiQuota) {
      $avertiQuota = $true
      Write-Host "  Attention : $msgJour messages envoyés aujourd'hui (limite du relais gratuit ~250). Je ralentis les envois." -ForegroundColor Yellow
    }

    # recensement des types de lignes : réécrit toutes les minutes s'il a changé
    if ($typesChange -and ($maintenant - $dernierTypes).TotalSeconds -ge 60) {
      $dernierTypes = $maintenant
      Ecrire-Types
    }

    # petit point de situation dans la fenêtre toutes les 2 minutes
    if (($maintenant - $dernierStatut).TotalSeconds -ge 120) {
      $dernierStatut = $maintenant
      Write-Host ("[" + $maintenant.ToString("HH:mm") + "] messages relais aujourd'hui : $msgJour - lignes lues : $nLues - envoyées : $nEnvoyees - répétitions ignorées : $nDoublons - en attente : " + $file.Count) -ForegroundColor DarkGray
    }
  }
} finally {
  Ecrire-Types
  [void](Envoyer ("$(Horo) [SeekerPC] bye"))
}
