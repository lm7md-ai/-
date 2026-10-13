import unittest
from map_geometry import encode_points

class MapGeometryTest(unittest.TestCase):
    def test_standard_signed_polyline_reference_and_repeated_points(self):
        self.assertEqual("_p~iF~ps|U_ulLnnqC_mqNvxq`@",encode_points([(3.85,-12.02),(4.07,-12.095),(4.3252,-12.6453)]))
        self.assertEqual("????",encode_points([(0,0),(0,0)]))

if __name__=='__main__':unittest.main()
