import pytest
from fastapi import HTTPException
from tubego_server.private_media import open_private_media,byte_range

@pytest.mark.parametrize('kind',['outside','relative_escape','symlink','directory_symlink','directory','missing','fifo'])
def test_media_open_rejects_unsafe_paths(tmp_path,kind):
    root=tmp_path/'media';root.mkdir();outside=tmp_path/'secret';outside.write_bytes(b'secret')
    (root/'link').symlink_to(outside);(root/'folder').symlink_to(tmp_path,target_is_directory=True);(root/'real-folder').mkdir()
    import os
    os.mkfifo(root/'fifo')
    path={'outside':str(outside),'relative_escape':'../secret','symlink':'link','directory_symlink':'folder/secret','directory':'real-folder','missing':'absent','fifo':'fifo'}[kind]
    with pytest.raises(HTTPException) as error:open_private_media(root,path)
    assert error.value.status_code==404


def test_media_open_relative_and_absolute_regular_file(tmp_path):
    root=tmp_path/'media';root.mkdir();path=root/'video';path.write_bytes(b'0123456789')
    for stored in ('video',str(path)):
        stream,metadata=open_private_media(root,stored)
        with stream:assert stream.read()==b'0123456789' and metadata.st_size==10


def test_single_range_validation():
    assert byte_range(None,10)==(0,9)
    assert byte_range('bytes=3-5',10)==(3,5)
    assert byte_range('bytes=-2',10)==(8,9)
    assert byte_range('bytes=4-',10)==(4,9)
    assert byte_range('bytes=0-99',10)==(0,9)
    assert byte_range(None,0)==(0,-1)
    for value in ('bytes=99-','bytes=4-2','bytes=0-2,4-5','bad','bytes=-0','bytes=0-'):
        size=0 if value=='bytes=0-' else 10
        with pytest.raises(HTTPException) as error:byte_range(value,size)
        assert error.value.status_code==416
