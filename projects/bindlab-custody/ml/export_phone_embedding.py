"""Export local pinned ESM2 weights for the BindLab phone CPU path."""
import hashlib
import json
from pathlib import Path
import torch
from transformers import EsmModel
from executorch.exir import to_edge

ROOT = Path(__file__).resolve().parents[3]
SOURCE = ROOT/'models/sources/esm2-8m-c731040fcd8d73dceaa04b0a8e6329b345b0f5df'
OUTPUT = ROOT/'models/esm2-8m/s25/bindlab-portable'


class Embedding(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, ids):
        hidden = self.model(input_ids=ids, attention_mask=(ids != 1).long(),
                            return_dict=False)[0]
        mask = ((ids != 0) & (ids != 1) & (ids != 2)).float().unsqueeze(-1)
        return (hidden * mask).sum(1) / mask.sum(1).clamp(min=1)


def main():
    torch.set_num_threads(4)
    model = Embedding(EsmModel.from_pretrained(SOURCE, local_files_only=True,
        add_pooling_layer=False, attn_implementation='eager')).eval()
    ids = torch.ones(1,256,dtype=torch.long)
    ids[0,:7] = torch.tensor([0,5,6,7,8,9,2])
    with torch.no_grad(): expected = model(ids)
    print('Exporting embedding', list(expected.shape), flush=True)
    program = to_edge(torch.export.export(model,(ids,))).to_executorch()
    OUTPUT.mkdir(parents=True,exist_ok=True)
    pte = OUTPUT/'esm2_8M_portable-cpu.pte'
    pte.write_bytes(program.buffer)
    metadata = {'model':'facebook/esm2_t6_8M_UR50D',
        'revision':'c731040fcd8d73dceaa04b0a8e6329b345b0f5df',
        'weights_sha256':hashlib.sha256((SOURCE/'model.safetensors').read_bytes()).hexdigest(),
        'pte_sha256':hashlib.sha256(pte.read_bytes()).hexdigest(),
        'input':{'shape':[1,256],'dtype':'int64'},'output':{'shape':[1,320],'dtype':'float32'},
        'backend':'portable-cpu','executorch':'1.5.1','torch':torch.__version__,
        'pooling':'Mean of residue tokens excluding CLS/EOS/PAD',
        'scope':'Sequence embeddings; no contacts, structure prediction, or assay validation',
        'fixture':{'ids':ids[0].tolist(),'expected_embedding':expected[0].tolist()}}
    (OUTPUT/'manifest.json').write_text(json.dumps(metadata,indent=2)+'\n')
    print('Exported',pte,'bytes',pte.stat().st_size,flush=True)


if __name__ == '__main__': main()
