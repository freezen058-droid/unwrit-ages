"""Sign data-only challenge content with a local key. Never deploys or touches wallets."""
import argparse, base64, json, re
from pathlib import Path
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

parser = argparse.ArgumentParser()
parser.add_argument('--manifest', required=True)
parser.add_argument('--key-file', required=True)
parser.add_argument('--output', required=True)
args = parser.parse_args()
data = json.loads(Path(args.manifest).read_text(encoding='utf-8-sig'))
assert data['schema'] == 1 and data['engine'] == 1
assert 1 <= data['revision'] <= 1000000
packs = data['packs']; assert 1 <= len(packs) <= 10
seen_ids = set(); seen_labels = set()
metrics = {'exploredTiles', 'gold', 'population', 'cities', 'happiness', 'peace', 'wonders', 'technologies'}
for pack in packs:
    assert re.fullmatch('[a-z0-9-]{1,64}', pack['id'])
    assert 1 <= pack['revision'] <= 1000000
    assert all(1 <= len(pack[k]) <= 160 for k in ['title','titleTw','titleCn'])
    assert all(1 <= len(pack[k]) <= 4 and all(1 <= len(s) <= 300 for s in pack[k]) for k in ['guide','guideTw','guideCn'])
    assert 1 <= len(pack['goals']) <= 12
    for goal in pack['goals']:
        assert re.fullmatch('[a-z0-9-]{1,64}', goal['id']) and goal['id'] not in seen_ids
        assert goal['label'] not in seen_labels
        seen_ids.add(goal['id']); seen_labels.add(goal['label'])
        assert goal['packId'] == pack['id'] and goal['packRevision'] == pack['revision']
        assert goal.get('engine',1) == 1
        assert all(1 <= len(goal[k]) <= 32 for k in ['label','labelTw','labelCn'])
        assert all(1 <= len(goal[k]) <= 160 for k in ['title','titleTw','titleCn'])
        assert goal.get('mode','milestone') in ['milestone','deadline','consecutive']
        assert goal.get('combination','all') in ['all','any'] and 1 <= goal.get('turns',1) <= 300
        assert not goal.get('outcomeBranches') and not goal.get('resultTiers')
        assert not goal.get('initialized',False) and not goal.get('baseline',{})
        assert goal.get('completedTurn',-1) == -1 and goal.get('lastObservedTurn',-1) == -1 and goal.get('streak',0) == 0
        assert 1 <= len(goal['conditions']) <= 8
        for condition in goal['conditions']:
            assert condition['metric'] in metrics
            assert condition.get('comparison','atLeast') in ['atLeast','atMost','equal']
            assert -1000000 <= condition['target'] <= 1000000
payload = json.dumps(data, ensure_ascii=False, separators=(',',':')).encode('utf-8')
assert len(payload) <= 128*1024
key = serialization.load_pem_private_key(Path(args.key_file).read_bytes(), password=None)
public = base64.b64encode(key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode()
source = Path(__file__).resolve().parents[1] / 'core/src/com/unciv/logic/chain/ChallengePacks.kt'
assert public in source.read_text(encoding='utf-8'), 'Signing key does not match the installed engine'
signature = key.sign(payload, padding.PKCS1v15(), hashes.SHA256())
envelope = json.dumps({'payload':base64.b64encode(payload).decode(),'signature':base64.b64encode(signature).decode()},separators=(',',':'))
assert len(envelope.encode()) <= 128*1024
out = Path(args.output); out.parent.mkdir(parents=True,exist_ok=True); out.write_text(envelope,encoding='utf-8')
print('Signed manifest revision',data['revision'],'with',sum(p['enabled'] for p in packs),'enabled packs. Key material was not copied.')
