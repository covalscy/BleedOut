from PIL import Image, ImageDraw
import os, shutil

size = 32
img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
draw = ImageDraw.Draw(img)

aw = 10
al = 24
cx = cy = size // 2

# White cross
draw.rectangle([cx-aw//2, cy-al//2, cx+aw//2, cy+al//2], fill=(255, 255, 255, 255))
draw.rectangle([cx-al//2, cy-aw//2, cx+al//2, cy+aw//2], fill=(255, 255, 255, 255))

# Subtle outline
oi = Image.new('RGBA', (size, size), (0, 0, 0, 0))
od = ImageDraw.Draw(oi)
ow = aw + 2
ol = al + 2
od.rectangle([cx-ow//2, cy-ol//2, cx+ow//2, cy+ol//2], fill=(200, 200, 200, 100))
od.rectangle([cx-ol//2, cy-ow//2, cx+ol//2, cy+ow//2], fill=(200, 200, 200, 100))

result = Image.alpha_composite(oi, img)

# Save to current dir first
result.save('knocked_indicator.png')

# Copy to ESP-mod textures
esp_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'ESP-mod', 'src', 'main', 'resources', 'assets', 'espmod', 'textures', 'overlay', 'teammate')
os.makedirs(esp_path, exist_ok=True)
shutil.copy2('knocked_indicator.png', os.path.join(esp_path, 'knocked_indicator.png'))

print(f'Done! Saved to {esp_path}')
