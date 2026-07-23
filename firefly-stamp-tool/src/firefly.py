"""Client Adobe Firefly Services - Generative Fill (V3) de XOA stamp cu.

Luong: OAuth (client_credentials) -> upload anh + upload mask -> goi /v3/images/fill
       -> lay anh ket qua (da xoa vung stamp).

Neu bat ky buoc nao loi => tra ve None, engine tu dong dung che do 'cover'
=> tool KHONG BAO GIO gay ket qua.

Ghi chu: endpoint theo tai lieu Firefly Services v3. Neu Adobe doi API,
chi can sua cac hang so URL ben duoi.
"""
import io
import time
import requests
from PIL import Image

IMS_TOKEN_URL = "https://ims-na1.adobelogin.com/ims/token/v3"
FF_BASE = "https://firefly-api.adobe.io"
UPLOAD_URL = FF_BASE + "/v2/storage/image"
FILL_URL = FF_BASE + "/v3/images/fill"
SCOPES = "openid,AdobeID,firefly_api,ff_apis"


class FireflyClient:
    def __init__(self, client_id, client_secret, fill_prompt, region_height_frac=0.28):
        self.cid = client_id
        self.secret = client_secret
        self.prompt = fill_prompt
        self.region_h = region_height_frac
        self._token = None
        self._token_exp = 0

    # ---- auth -------------------------------------------------------------
    def _get_token(self):
        if self._token and time.time() < self._token_exp - 60:
            return self._token
        r = requests.post(IMS_TOKEN_URL, data={
            "grant_type": "client_credentials",
            "client_id": self.cid,
            "client_secret": self.secret,
            "scope": SCOPES,
        }, timeout=30)
        r.raise_for_status()
        j = r.json()
        self._token = j["access_token"]
        self._token_exp = time.time() + int(j.get("expires_in", 3600))
        return self._token

    def _headers(self, content_type=None):
        h = {"Authorization": "Bearer " + self._get_token(), "x-api-key": self.cid}
        if content_type:
            h["Content-Type"] = content_type
        return h

    # ---- storage ----------------------------------------------------------
    def _upload(self, pil_img):
        buf = io.BytesIO()
        pil_img.convert("RGB").save(buf, format="PNG")
        buf.seek(0)
        r = requests.post(UPLOAD_URL, headers=self._headers("image/png"),
                          data=buf.getvalue(), timeout=120)
        r.raise_for_status()
        j = r.json()
        # tra ve dang {"images":[{"id":"..."}]}
        return j["images"][0]["id"]

    # ---- fill -------------------------------------------------------------
    def _make_mask(self, size):
        """Mask: trang o vung stamp (duoi anh), den phan con lai."""
        from PIL import ImageDraw
        W, H = size
        mask = Image.new("L", (W, H), 0)
        band_h = int(H * self.region_h)
        ImageDraw.Draw(mask).rectangle([0, H - band_h, W, H], fill=255)
        return mask

    def remove_stamp(self, pil_img):
        try:
            src_id = self._upload(pil_img)
            mask_id = self._upload(self._make_mask(pil_img.size).convert("RGB"))
            body = {
                "numVariations": 1,
                "prompt": self.prompt,
                "image": {
                    "source": {"uploadId": src_id},
                    "mask": {"uploadId": mask_id},
                },
            }
            r = requests.post(FILL_URL, headers=self._headers("application/json"),
                              json=body, timeout=180)
            r.raise_for_status()
            out = r.json()
            url = self._extract_output_url(out)
            if not url:
                return None
            img_r = requests.get(url, timeout=120)
            img_r.raise_for_status()
            return Image.open(io.BytesIO(img_r.content)).convert("RGB")
        except Exception as e:
            print(f"    [Firefly] loi ({e}) -> chuyen sang che do 'cover'.")
            return None

    @staticmethod
    def _extract_output_url(resp):
        # ho tro ca dang dong bo lan async
        try:
            outs = resp.get("outputs") or resp.get("result", {}).get("outputs")
            if outs:
                img = outs[0].get("image", {})
                return img.get("url") or img.get("presignedUrl") or img.get("href")
        except Exception:
            pass
        return None
