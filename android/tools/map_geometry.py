"""OSM display geometry encoding: standard signed polyline integers at precision 6."""
def encode_points(points):
    result=[]; previous=[0,0]
    for point in points:
        for axis,value in enumerate(point):
            current=round(value*1_000_000); delta=current-previous[axis]; previous[axis]=current
            number=~(delta<<1) if delta<0 else delta<<1
            while number>=32:
                result.append(chr(((number&31)|32)+63)); number>>=5
            result.append(chr(number+63))
    return ''.join(result)
