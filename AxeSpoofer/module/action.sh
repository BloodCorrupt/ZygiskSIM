#!/system/bin/sh
# Axe Spoofer — Action button script (KernelSU / APatch / Magisk)
# Generates new random values for all enabled identifiers

MODDIR="/data/adb/modules/axespoofer"
CONFIG_FILE="${MODDIR}/config.json"

if [ ! -d "${MODDIR}" ]; then
    MODDIR="$(dirname "$0")"
    CONFIG_FILE="${MODDIR}/config.json"
fi

# Function to generate random hex string of given length
rand_hex() {
    len=$1
    if [ -c /dev/urandom ]; then
        head -c "$((len / 2 + 1))" /dev/urandom | od -An -tx1 | tr -d ' \n' | cut -c 1-"${len}"
    else
        awk -v len="$len" 'BEGIN {
            srand();
            chars="0123456789abcdef";
            out="";
            for(i=1;i<=len;i++) {
                r = int(rand()*16)+1;
                out = out substr(chars,r,1);
            }
            print out;
        }'
    fi
}

# Function to generate random UUID v4
rand_uuid() {
    h1=$(rand_hex 8)
    h2=$(rand_hex 4)
    h3="4$(rand_hex 3)"
    c=$(rand_hex 1)
    case "$c" in
        0|1|2|3) c="8" ;;
        4|5|6|7) c="9" ;;
        8|9|a|b) c="a" ;;
        *) c="b" ;;
    esac
    h4="${c}$(rand_hex 3)"
    h5=$(rand_hex 12)
    echo "${h1}-${h2}-${h3}-${h4}-${h5}"
}

NEW_ANDROID_ID=$(rand_hex 16)
NEW_GSF_ID=$(rand_hex 16)
NEW_ADS_ID=$(rand_uuid)
NEW_APP_SET_ID=$(rand_uuid)
NEW_MEDIA_DRM_ID=$(rand_hex 64)

# Update config.json if python or sed/awk is available
if command -v python3 >/dev/null 2>&1; then
    python3 -c "
import json
path = '${CONFIG_FILE}'
try:
    with open(path, 'r') as f:
        data = json.load(f)
except Exception:
    data = {'enabled': True, 'targets': ['travel.eskimo.esim', 'com.wonet.usims', 'com.airalo.android']}

if 'android_id' not in data: data['android_id'] = {'enabled': True}
if 'gsf_id' not in data: data['gsf_id'] = {'enabled': True}
if 'ads_id' not in data: data['ads_id'] = {'enabled': True}
if 'app_set_id' not in data: data['app_set_id'] = {'enabled': True}
if 'media_drm_id' not in data: data['media_drm_id'] = {'enabled': True}

data['android_id']['value'] = '${NEW_ANDROID_ID}'
data['gsf_id']['value'] = '${NEW_GSF_ID}'
data['ads_id']['value'] = '${NEW_ADS_ID}'
data['app_set_id']['value'] = '${NEW_APP_SET_ID}'
data['media_drm_id']['value'] = '${NEW_MEDIA_DRM_ID}'

with open(path, 'w') as f:
    json.dump(data, f, indent=2)
"
else
    # Shell fallback JSON generation
    cat <<EOF > "${CONFIG_FILE}"
{
  "enabled": true,
  "targets": [
    "travel.eskimo.esim",
    "com.wonet.usims",
    "com.airalo.android",
    "com.trustroam",
    "com.nomad.app",
    "com.holafly.android",
    "com.samsung.android.euicc"
  ],
  "android_id": {
    "enabled": true,
    "value": "${NEW_ANDROID_ID}"
  },
  "gsf_id": {
    "enabled": true,
    "value": "${NEW_GSF_ID}"
  },
  "ads_id": {
    "enabled": true,
    "value": "${NEW_ADS_ID}"
  },
  "app_set_id": {
    "enabled": true,
    "value": "${NEW_APP_SET_ID}"
  },
  "media_drm_id": {
    "enabled": true,
    "value": "${NEW_MEDIA_DRM_ID}"
  }
}
EOF
fi

chmod 644 "${CONFIG_FILE}"

echo "[Axe Spoofer] Action executed: IDs randomized successfully!"
echo "  Android ID:   ${NEW_ANDROID_ID}"
echo "  GSF ID:       ${NEW_GSF_ID}"
echo "  Ads ID:       ${NEW_ADS_ID}"
echo "  App Set ID:   ${NEW_APP_SET_ID}"
echo "  Media DRM ID: ${NEW_MEDIA_DRM_ID}"
